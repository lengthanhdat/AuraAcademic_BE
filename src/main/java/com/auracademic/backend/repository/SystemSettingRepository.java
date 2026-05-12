package com.auracademic.backend.repository;

import com.auracademic.backend.model.SystemSetting;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SystemSettingRepository extends MongoRepository<SystemSetting, String> {
}
