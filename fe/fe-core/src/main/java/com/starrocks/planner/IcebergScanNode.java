// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.planner;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;
import com.starrocks.analysis.DescriptorTable;
import com.starrocks.analysis.LiteralExpr;
import com.starrocks.analysis.SlotDescriptor;
import com.starrocks.analysis.SlotId;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.IcebergTable;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.UserException;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.connector.CatalogConnector;
import com.starrocks.connector.GetRemoteFilesParams;
import com.starrocks.connector.PartitionUtil;
import com.starrocks.connector.RemoteFileInfo;
import com.starrocks.connector.TableVersionRange;
import com.starrocks.connector.iceberg.IcebergApiConverter;
import com.starrocks.connector.iceberg.IcebergRemoteFileDesc;
import com.starrocks.credential.CloudConfiguration;
import com.starrocks.credential.CloudConfigurationFactory;
import com.starrocks.credential.CloudType;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.plan.HDFSScanNodePredicates;
import com.starrocks.thrift.TExplainLevel;
import com.starrocks.thrift.TExpr;
import com.starrocks.thrift.THdfsScanNode;
import com.starrocks.thrift.THdfsScanRange;
import com.starrocks.thrift.TIcebergDeleteFile;
import com.starrocks.thrift.TIcebergFileContent;
import com.starrocks.thrift.TNetworkAddress;
import com.starrocks.thrift.TPlanNode;
import com.starrocks.thrift.TPlanNodeType;
import com.starrocks.thrift.TScanRange;
import com.starrocks.thrift.TScanRangeLocation;
import com.starrocks.thrift.TScanRangeLocations;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileContent;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.types.Types;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.starrocks.catalog.IcebergTable.DATA_SEQUENCE_NUMBER;
import static com.starrocks.catalog.IcebergTable.SPEC_ID;
import static com.starrocks.server.CatalogMgr.ResourceMappingCatalog.isResourceMappingCatalog;

public class IcebergScanNode extends ScanNode {
    private static final Logger LOG = LogManager.getLogger(IcebergScanNode.class);

    protected final IcebergTable icebergTable;
    private final HDFSScanNodePredicates scanNodePredicates = new HDFSScanNodePredicates();
    protected final List<TScanRangeLocations> result = new ArrayList<>();
    private ScalarOperator icebergJobPlanningPredicate = null;
    private CloudConfiguration cloudConfiguration = null;
    protected Optional<Long> snapshotId;
    private final List<Integer> extendedColumnSlotIds = new ArrayList<>();

    public IcebergScanNode(PlanNodeId id, TupleDescriptor desc, String planNodeName) {
        super(id, desc, planNodeName);
        this.icebergTable = (IcebergTable) desc.getTable();
        setupCloudCredential();
    }

    private void setupCloudCredential() {
        String catalogName = icebergTable.getCatalogName();
        if (catalogName == null) {
            return;
        }

        // Hard coding here
        // Try to get tabular signed temporary credential
        CloudConfiguration tabularTempCloudConfiguration = CloudConfigurationFactory.
                buildCloudConfigurationForTabular(icebergTable.getNativeTable().io().properties());
        if (tabularTempCloudConfiguration.getCloudType() != CloudType.DEFAULT) {
            // If we get CloudConfiguration succeed from iceberg FileIO's properties, we just using it.
            cloudConfiguration = tabularTempCloudConfiguration;
        } else {
            CatalogConnector connector = GlobalStateMgr.getCurrentState().getConnectorMgr().getConnector(catalogName);
            Preconditions.checkState(connector != null,
                    String.format("connector of catalog %s should not be null", catalogName));
            cloudConfiguration = connector.getMetadata().getCloudConfiguration();
            Preconditions.checkState(cloudConfiguration != null,
                    String.format("cloudConfiguration of catalog %s should not be null", catalogName));
        }
    }

    public void preProcessIcebergPredicate(ScalarOperator predicate) {
        this.icebergJobPlanningPredicate = predicate;
    }

    // for unit tests
    public ScalarOperator getIcebergJobPlanningPredicate() {
        return icebergJobPlanningPredicate;
    }

    // for unit tests
    public List<Integer> getExtendedColumnSlotIds() {
        return extendedColumnSlotIds;
    }

    @Override
    public List<TScanRangeLocations> getScanRangeLocations(long maxScanRangeLength) {
        return result;
    }

    public void setSnapshotId(Optional<Long> snapshotId) {
        this.snapshotId = snapshotId;
    }


    public static BiMap<Integer, PartitionField> getIdentityPartitions(PartitionSpec partitionSpec) {
        // TODO: expose transform information in Iceberg library
        BiMap<Integer, PartitionField> columns = HashBiMap.create();
        if (!ConnectContext.get().getSessionVariable().getEnableIcebergIdentityColumnOptimize()) {
            return columns;
        }
        for (int i = 0; i < partitionSpec.fields().size(); i++) {
            PartitionField field = partitionSpec.fields().get(i);
            if (field.transform().isIdentity()) {
                columns.put(i, field);
            }
        }
        return columns;
    }

    protected PartitionKey getPartitionKey(StructLike partition, PartitionSpec spec, List<Integer> indexes,
                                         BiMap<Integer, PartitionField> indexToField) throws AnalysisException {
        List<String> partitionValues = new ArrayList<>();
        List<Column> cols = new ArrayList<>();
        indexes.forEach((index) -> {
            PartitionField field = indexToField.get(index);
            int id = field.sourceId();
            org.apache.iceberg.types.Type type = spec.schema().findType(id);
            Class<?> javaClass = type.typeId().javaClass();

            String partitionValue;
            partitionValue = field.transform().toHumanString(type,
                    PartitionUtil.getPartitionValue(partition, index, javaClass));

            // currently starrocks date literal only support local datetime
            if (type.equals(Types.TimestampType.withZone())) {
                partitionValue = ChronoUnit.MICROS.addTo(Instant.ofEpochSecond(0).atZone(TimeUtils.getTimeZone().toZoneId()),
                        PartitionUtil.getPartitionValue(partition, index, javaClass)).toLocalDateTime().toString();
            }
            partitionValues.add(partitionValue);

            cols.add(icebergTable.getColumn(field.name()));
        });

        return PartitionUtil.createPartitionKey(partitionValues, cols, Table.TableType.ICEBERG);
    }


    public void setupScanRangeLocations(DescriptorTable descTbl) throws UserException {
        Preconditions.checkNotNull(snapshotId, "snapshot id is null");
        if (snapshotId.isEmpty()) {
            LOG.warn(String.format("Table %s has no snapshot!", icebergTable.getRemoteTableName()));
            return;
        }

        GetRemoteFilesParams params =
                GetRemoteFilesParams.newBuilder().setTableVersionRange(TableVersionRange.withEnd(snapshotId))
                        .setPredicate(icebergJobPlanningPredicate).build();
        List<RemoteFileInfo> splits = GlobalStateMgr.getCurrentState().getMetadataMgr().getRemoteFiles(icebergTable, params);

        if (splits.isEmpty()) {
            LOG.warn("There is no scan tasks after planFies on {}.{} and predicate: [{}]",
                    icebergTable.getRemoteDbName(), icebergTable.getRemoteTableName(), icebergJobPlanningPredicate);
            return;
        }

        IcebergRemoteFileDesc remoteFileDesc = (IcebergRemoteFileDesc) splits.get(0).getFiles().get(0);
        if (remoteFileDesc == null) {
            LOG.warn("There is no scan tasks after planFies on {}.{} and predicate: [{}]",
                    icebergTable.getRemoteDbName(), icebergTable.getRemoteTableName(), icebergJobPlanningPredicate);
            return;
        }

        Map<StructLike, Long> partitionKeyToId = Maps.newHashMap();
        Map<Long, List<Integer>> idToPartitionSlots = Maps.newHashMap();
        for (FileScanTask task : remoteFileDesc.getIcebergScanTasks()) {
            buildScanRanges(task, partitionKeyToId, idToPartitionSlots, descTbl);
        }

        scanNodePredicates.setSelectedPartitionIds(partitionKeyToId.values());
    }

    protected void buildScanRanges(FileScanTask task, Map<StructLike, Long> partitionKeyToId,
                                   Map<Long, List<Integer>> idToParSlots, DescriptorTable descTbl) throws AnalysisException {
        THdfsScanRange hdfsScanRange = buildScanRange(task, task.file(), partitionKeyToId, idToParSlots, descTbl);

        List<TIcebergDeleteFile> deleteFiles = new ArrayList<>();
        for (DeleteFile deleteFile : task.deletes()) {
            FileContent content = deleteFile.content();
            if (content == FileContent.EQUALITY_DELETES) {
                continue;
            }

            TIcebergDeleteFile target = new TIcebergDeleteFile();
            target.setFull_path(deleteFile.path().toString());
            target.setFile_content(TIcebergFileContent.POSITION_DELETES);
            target.setLength(deleteFile.fileSizeInBytes());
            deleteFiles.add(target);
        }

        if (!deleteFiles.isEmpty()) {
            hdfsScanRange.setDelete_files(deleteFiles);
        }

        fillResult(hdfsScanRange);
    }

    protected void fillResult(THdfsScanRange hdfsScanRange) {
        TScanRangeLocations scanRangeLocations = new TScanRangeLocations();
        TScanRange scanRange = new TScanRange();
        scanRange.setHdfs_scan_range(hdfsScanRange);
        scanRangeLocations.setScan_range(scanRange);

        TScanRangeLocation scanRangeLocation = new TScanRangeLocation(new TNetworkAddress("-1", -1));
        scanRangeLocations.addToLocations(scanRangeLocation);
        result.add(scanRangeLocations);
    }

    public HDFSScanNodePredicates getScanNodePredicates() {
        return scanNodePredicates;
    }

    protected THdfsScanRange buildScanRange(FileScanTask task, ContentFile<?> file, Map<StructLike, Long> partitionKeyToId,
                                  Map<Long, List<Integer>> idToPartitionSlots, DescriptorTable descTbl) throws AnalysisException {
        StructLike partition = file.partition();
        long partitionId = 0;
        if (!partitionKeyToId.containsKey(partition)) {
            partitionId = icebergTable.nextPartitionId();
            partitionKeyToId.put(partition, partitionId);
            BiMap<Integer, PartitionField> indexToField = getIdentityPartitions(task.spec());
            if (!indexToField.isEmpty()) {
                List<Integer> partitionSlotIds = task.spec().fields().stream()
                        .map(x -> desc.getColumnSlot(x.name()))
                        .filter(Objects::nonNull)
                        .map(SlotDescriptor::getId)
                        .map(SlotId::asInt)
                        .collect(Collectors.toList());
                List<Integer> indexes = task.spec().fields().stream()
                        .filter(x -> desc.getColumnSlot(x.name()) != null)
                        .map(x -> indexToField.inverse().get(x))
                        .collect(Collectors.toList());
                PartitionKey partitionKey = getPartitionKey(partition, task.spec(), indexes, indexToField);

                DescriptorTable.ReferencedPartitionInfo partitionInfo =
                        new DescriptorTable.ReferencedPartitionInfo(partitionId, partitionKey);

                descTbl.addReferencedPartitions(icebergTable, partitionInfo);
                idToPartitionSlots.put(partitionId, partitionSlotIds);
            }
        }

        partitionId = partitionKeyToId.get(partition);

        THdfsScanRange hdfsScanRange = new THdfsScanRange();
        if (file.path().toString().startsWith(icebergTable.getTableLocation())) {
            hdfsScanRange.setRelative_path(file.path().toString().substring(icebergTable.getTableLocation().length()));
        } else {
            hdfsScanRange.setFull_path(file.path().toString());
        }

        hdfsScanRange.setOffset(file.content() == FileContent.DATA ? task.start() : 0);
        hdfsScanRange.setLength(file.content() == FileContent.DATA ? task.length() : file.fileSizeInBytes());
        // For iceberg table we do not need partition id
        if (!idToPartitionSlots.containsKey(partitionId)) {
            hdfsScanRange.setPartition_id(-1);
        } else {
            hdfsScanRange.setPartition_id(partitionId);
            hdfsScanRange.setIdentity_partition_slot_ids(idToPartitionSlots.get(partitionId));
        }
        hdfsScanRange.setFile_length(file.fileSizeInBytes());
        // Iceberg data file cannot be overwritten
        hdfsScanRange.setModification_time(0);
        hdfsScanRange.setFile_format(IcebergApiConverter.getHdfsFileFormat(file.format()).toThrift());

        // fill extended column value
        List<SlotDescriptor> slots = desc.getSlots();
        Map<Integer, TExpr> extendedColumns = new HashMap<>();
        for (SlotDescriptor slot : slots) {
            String name = slot.getColumn().getName();
            if (name.equalsIgnoreCase(DATA_SEQUENCE_NUMBER) || name.equalsIgnoreCase(SPEC_ID)) {
                LiteralExpr value;
                if (name.equalsIgnoreCase(DATA_SEQUENCE_NUMBER)) {
                    value = LiteralExpr.create(String.valueOf(file.dataSequenceNumber()), Type.BIGINT);
                } else {
                    value = LiteralExpr.create(String.valueOf(file.specId()), Type.INT);
                }

                extendedColumns.put(slot.getId().asInt(), value.treeToThrift());
                if (!extendedColumnSlotIds.contains(slot.getId().asInt())) {
                    extendedColumnSlotIds.add(slot.getId().asInt());
                }
            }
        }
        hdfsScanRange.setExtended_columns(extendedColumns);
        return hdfsScanRange;
    }

    @Override
    protected String debugString() {
        MoreObjects.ToStringHelper helper = MoreObjects.toStringHelper(this);
        helper.addValue(super.debugString());
        helper.add("icebergTable=", icebergTable.getName());
        return helper.toString();
    }

    @Override
    protected String getNodeExplainString(String prefix, TExplainLevel detailLevel) {
        StringBuilder output = new StringBuilder();

        output.append(prefix).append("TABLE: ")
                .append(icebergTable.getRemoteDbName())
                .append(".")
                .append(icebergTable.getName())
                .append("\n");

        if (null != sortColumn) {
            output.append(prefix).append("SORT COLUMN: ").append(sortColumn).append("\n");
        }
        if (!conjuncts.isEmpty()) {
            output.append(prefix).append("PREDICATES: ").append(
                    getExplainString(conjuncts)).append("\n");
        }
        if (!scanNodePredicates.getMinMaxConjuncts().isEmpty()) {
            output.append(prefix).append("MIN/MAX PREDICATES: ").append(
                    getExplainString(scanNodePredicates.getMinMaxConjuncts())).append("\n");
        }

        output.append(prefix).append(String.format("cardinality=%s", cardinality));
        output.append("\n");

        output.append(prefix).append(String.format("avgRowSize=%s", avgRowSize));
        output.append("\n");

        if (detailLevel == TExplainLevel.VERBOSE) {
            HdfsScanNode.appendDataCacheOptionsInExplain(output, prefix, dataCacheOptions);

            for (SlotDescriptor slotDescriptor : desc.getSlots()) {
                Type type = slotDescriptor.getOriginType();
                if (type.isComplexType()) {
                    output.append(prefix)
                            .append(String.format("Pruned type: %d <-> [%s]\n", slotDescriptor.getId().asInt(), type));
                }
            }
        }

        if (detailLevel == TExplainLevel.VERBOSE && !isResourceMappingCatalog(icebergTable.getCatalogName())) {
            List<String> partitionNames = GlobalStateMgr.getCurrentState().getMetadataMgr().listPartitionNames(
                    icebergTable.getCatalogName(), icebergTable.getRemoteDbName(),
                    icebergTable.getRemoteTableName(), TableVersionRange.withEnd(snapshotId));

            output.append(prefix).append(
                    String.format("partitions=%s/%s", scanNodePredicates.getSelectedPartitionIds().size(),
                            partitionNames.size() == 0 ? 1 : partitionNames.size()));
            output.append("\n");
        }

        return output.toString();
    }

    @Override
    protected void toThrift(TPlanNode msg) {
        msg.node_type = TPlanNodeType.HDFS_SCAN_NODE;
        THdfsScanNode tHdfsScanNode = new THdfsScanNode();
        tHdfsScanNode.setTuple_id(desc.getId().asInt());
        msg.hdfs_scan_node = tHdfsScanNode;

        String sqlPredicates = getExplainString(conjuncts);
        msg.hdfs_scan_node.setSql_predicates(sqlPredicates);
        msg.hdfs_scan_node.setExtended_slot_ids(extendedColumnSlotIds);
        msg.hdfs_scan_node.setTable_name(icebergTable.getName());
        HdfsScanNode.setScanOptimizeOptionToThrift(tHdfsScanNode, this);
        HdfsScanNode.setCloudConfigurationToThrift(tHdfsScanNode, cloudConfiguration);
        HdfsScanNode.setMinMaxConjunctsToThrift(tHdfsScanNode, this, this.getScanNodePredicates());
        HdfsScanNode.setDataCacheOptionsToThrift(tHdfsScanNode, dataCacheOptions);
    }

    @Override
    public boolean canUseRuntimeAdaptiveDop() {
        return true;
    }

    @Override
    protected boolean supportTopNRuntimeFilter() {
        return !icebergTable.isV2Format();
    }
}
