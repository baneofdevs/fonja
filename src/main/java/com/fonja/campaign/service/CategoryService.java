package com.fonja.campaign.service;

import com.fonja.campaign.dto.CategoryResponse;
import com.fonja.campaign.dto.CreateCategoryRequest;
import com.fonja.campaign.dto.UpdateCategoryRequest;
import com.fonja.campaign.entity.Category;
import com.fonja.campaign.repository.CategoryRepository;
import com.fonja.common.exception.ApiException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The only way other modules resolve a category — they pass slugs, never ids or entities. */
@Service
public class CategoryService {

    private static final int DEFAULT_SORT_ORDER = 1000;

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /** What pickers and filters show. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> listActive() {
        return categoryRepository.findAllByActiveTrueOrderBySortOrderAscNameAsc().stream()
                .map(CategoryResponse::from)
                .toList();
    }

    /** Admin view, including deactivated rows. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> listAll() {
        return categoryRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(CategoryResponse::from)
                .toList();
    }

    /**
     * Resolves a slug a client chose to assign. Inactive categories are refused: they are hidden
     * from pickers, so a request naming one is stale or hand-crafted.
     */
    @Transactional(readOnly = true)
    public Category requireAssignable(String slug) {
        return categoryRepository
                .findBySlug(normalize(slug))
                .filter(Category::isActive)
                .orElseThrow(() -> ApiException.badRequest("Unknown category: " + slug));
    }

    /** Null in, null out — for filters where "no category" means "every category". */
    @Transactional(readOnly = true)
    public Category findBySlugOrNull(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        return categoryRepository.findBySlug(normalize(slug)).orElse(null);
    }

    /** Resolves a set of slugs, all or nothing, so a typo is reported rather than dropped. */
    @Transactional(readOnly = true)
    public List<Category> requireAssignable(Collection<String> slugs) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String slug : slugs) {
            wanted.add(normalize(slug));
        }
        List<Category> found = categoryRepository.findAllBySlugInAndActiveTrue(wanted);
        if (found.size() != wanted.size()) {
            Set<String> missing = new LinkedHashSet<>(wanted);
            found.forEach(c -> missing.remove(c.getSlug()));
            throw ApiException.badRequest("Unknown categories: " + String.join(", ", missing));
        }
        return found;
    }

    @Transactional
    public CategoryResponse create(CreateCategoryRequest request) {
        String slug = normalize(request.slug());
        if (categoryRepository.existsBySlug(slug)) {
            throw ApiException.conflict("A category with slug '" + slug + "' already exists");
        }
        Category category =
                new Category(
                        slug,
                        request.name().trim(),
                        blankToNull(request.emoji()),
                        request.sortOrder() == null ? DEFAULT_SORT_ORDER : request.sortOrder());
        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse update(Long id, UpdateCategoryRequest request) {
        Category category =
                categoryRepository
                        .findById(id)
                        .orElseThrow(() -> ApiException.notFound("Category not found"));
        if (request.name() != null) {
            category.setName(request.name().trim());
        }
        if (request.emoji() != null) {
            category.setEmoji(blankToNull(request.emoji()));
        }
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            if (!request.active() && category.isOthers()) {
                throw ApiException.conflict("The 'others' category cannot be deactivated");
            }
            category.setActive(request.active());
        }
        return CategoryResponse.from(category);
    }

    private static String normalize(String slug) {
        return slug == null ? "" : slug.trim().toLowerCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
