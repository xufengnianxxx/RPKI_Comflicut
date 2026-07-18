package com.rpki.conflictchecker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rpki.conflictchecker.entity.PairDetectionRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PairDetectionRecordMapper extends BaseMapper<PairDetectionRecord> {
}
