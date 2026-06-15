package net.donjiral.pingpong.post

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PostRepository : JpaRepository<Post, Long> {

    @Query(
        """
        SELECT p FROM Post p
        WHERE (:category IS NULL OR p.category = :category)
          AND (
            :q IS NULL
            OR LOWER(p.title) LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(p.content) LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(p.author) LIKE LOWER(CONCAT('%', :q, '%'))
          )
        ORDER BY p.createdAt DESC
        """
    )
    fun search(@Param("category") category: String?, @Param("q") q: String?, pageable: Pageable): Page<Post>
}

interface CommentRepository : JpaRepository<Comment, Long>
