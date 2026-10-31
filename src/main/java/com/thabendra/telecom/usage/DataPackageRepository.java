package com.thabendra.telecom.usage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataPackageRepository extends JpaRepository<DataPackage, Long> {

    // active: 'Y' on sale, 'N' no longer sold (PR-2)
    List<DataPackage> findByActive(String active);
}
