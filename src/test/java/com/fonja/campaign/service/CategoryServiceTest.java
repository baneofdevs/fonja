package com.fonja.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fonja.campaign.dto.CreateCategoryRequest;
import com.fonja.campaign.dto.UpdateCategoryRequest;
import com.fonja.campaign.entity.Category;
import com.fonja.campaign.repository.CategoryRepository;
import com.fonja.common.exception.ApiException;
import com.fonja.common.response.ResponseCode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock private CategoryRepository categoryRepository;

    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        categoryService = new CategoryService(categoryRepository);
    }

    private static Category category(String slug, boolean active) {
        Category category = new Category(slug, slug, null, 10);
        category.setActive(active);
        return category;
    }

    @Test
    void assignableResolvesSlugsCaseInsensitively() {
        when(categoryRepository.findBySlug("education"))
                .thenReturn(Optional.of(category("education", true)));

        assertThat(categoryService.requireAssignable(" Education ").getSlug())
                .isEqualTo("education");
    }

    @Test
    void anInactiveCategoryCannotBeAssigned() {
        when(categoryRepository.findBySlug("legal"))
                .thenReturn(Optional.of(category("legal", false)));

        assertThatThrownBy(() -> categoryService.requireAssignable("legal"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.VALIDATION_ERROR);
    }

    @Test
    void aBatchNamesEveryUnknownSlug() {
        when(categoryRepository.findAllBySlugInAndActiveTrue(any()))
                .thenReturn(List.of(category("education", true)));

        assertThatThrownBy(
                        () ->
                                categoryService.requireAssignable(
                                        Set.of("education", "pets", "cars")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("pets")
                .hasMessageContaining("cars");
    }

    @Test
    void aDuplicateSlugIsAConflict() {
        when(categoryRepository.existsBySlug("sports")).thenReturn(true);

        assertThatThrownBy(
                        () ->
                                categoryService.create(
                                        new CreateCategoryRequest("sports", "Sports", null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.CONFLICT);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void othersCannotBeDeactivated() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category("others", true)));

        assertThatThrownBy(
                        () ->
                                categoryService.update(
                                        1L, new UpdateCategoryRequest(null, null, null, false)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.CONFLICT);
    }
}
