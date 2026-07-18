package com.rpki.conflictchecker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rpki.conflictchecker.entity.FabricTxRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FabricTxRecordMapper extends BaseMapper<FabricTxRecord> {
}
