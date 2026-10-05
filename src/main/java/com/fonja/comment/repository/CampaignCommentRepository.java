package com.fonja.comment.repository;

import com.fonja.comment.entity.CampaignComment;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignCommentRepository extends JpaRepository<CampaignComment, Long> {

    /** A page of a campaign's top-level comments, newest first, with their authors. */
    @EntityGraph(attributePaths = "author")
    Page<CampaignComment> findByCampaignIdAndParentIdIsNullOrderByCreatedAtDescIdDesc(
            Long campaignId, Pageable pageable);

    /** Every reply to the comments on one page, oldest first, in a single query. */
    @EntityGraph(attributePaths = "author")
    List<CampaignComment> findByParentIdInOrderByCreatedAtAscIdAsc(Collection<Long> parentIds);

    /** Live (not deleted) comments and replies on a campaign — the number on the tab. */
    @Query(
            "select count(c) from CampaignComment c"
                    + " where c.campaign.id = :campaignId and c.deletedAt is null")
    long countLive(@Param("campaignId") Long campaignId);
}
