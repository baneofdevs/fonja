package com.fonja.campaign.repository;

import com.fonja.campaign.entity.Category;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByActiveTrueOrderBySortOrderAscNameAsc();

    List<Category> findAllByOrderBySortOrderAscNameAsc();

    Optional<Category> findBySlug(String slug);

    List<Category> findAllBySlugInAndActiveTrue(Collection<String> slugs);

    boolean existsBySlug(String slug);
}
