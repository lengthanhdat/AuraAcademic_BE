package com.auracademic.backend.repository;

import com.auracademic.backend.model.ExamBankFolder;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExamBankFolderRepository extends MongoRepository<ExamBankFolder, String> {
    List<ExamBankFolder> findByTeacherId(String teacherId);
}
