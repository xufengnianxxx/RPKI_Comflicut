package com.rpki.conflictchecker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rpki.conflictchecker.entity.ConflictRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConflictRecordMapper extends BaseMapper<ConflictRecord> {
}
