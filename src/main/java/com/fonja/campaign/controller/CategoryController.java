package com.fonja.campaign.controller;

import com.fonja.campaign.dto.CategoryResponse;
import com.fonja.campaign.dto.CreateCategoryRequest;
import com.fonja.campaign.dto.UpdateCategoryRequest;
import com.fonja.campaign.service.CategoryService;
import com.fonja.common.response.CustomResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/**
 * Campaign categories. The list is public — the Explore filters and the sign-up interests step both
 * render it before anyone has a token. Writes sit under {@code /admin}, which {@code
 * SecurityConfig} restricts to ADMIN.
 */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public CustomResponse<List<CategoryResponse>> list() {
        return CustomResponse.success(categoryService.listActive());
    }

    @GetMapping("/admin")
    public CustomResponse<List<CategoryResponse>> listAll() {
        return CustomResponse.success(categoryService.listAll());
    }

    @PostMapping("/admin")
    public CustomResponse<CategoryResponse> create(
            @Valid @RequestBody CreateCategoryRequest request) {
        return CustomResponse.success("Category created", categoryService.create(request));
    }

    @PatchMapping("/admin/{categoryId}")
    public CustomResponse<CategoryResponse> update(
            @PathVariable Long categoryId, @Valid @RequestBody UpdateCategoryRequest request) {
        return CustomResponse.success(
                "Category updated", categoryService.update(categoryId, request));
    }
}
