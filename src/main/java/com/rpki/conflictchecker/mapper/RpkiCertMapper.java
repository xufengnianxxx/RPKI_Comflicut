package com.rpki.conflictchecker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rpki.conflictchecker.entity.RpkiCert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RpkiCertMapper extends BaseMapper<RpkiCert> {
    
    /**
     * 查询所有包含冲突的证书
     */
    @Select("SELECT * FROM rpki_cert WHERE has_conflict = true")
    List<RpkiCert> findAllConflictCerts();

    @Select("SELECT * FROM rpki_cert WHERE rir = #{rir}")
    List<RpkiCert> findByRir(String rir);
}
