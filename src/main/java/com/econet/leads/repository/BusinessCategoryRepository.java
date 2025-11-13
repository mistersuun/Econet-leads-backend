package com.econet.leads.repository;

import com.econet.leads.model.BusinessCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BusinessCategoryRepository extends JpaRepository<BusinessCategory, UUID> {

    Optional<BusinessCategory> findByCategoryName(String categoryName);
}
