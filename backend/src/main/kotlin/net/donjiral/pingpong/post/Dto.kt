package net.donjiral.pingpong.post

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

// ===== 요청 =====
data class PostCreateRequest(
    @field:NotBlank val category: String,
    @field:NotBlank @field:Size(max = 200) val title: String,
    @field:Size(max = 50) val author: String?,
    @field:NotBlank val content: String,
    @field:Size(max = 500) val media: String?,
    @field:NotBlank @field:Size(min = 1, max = 100) val password: String
)

data class PostUpdateRequest(
    @field:NotBlank val title: String,
    @field:NotBlank val content: String,
    val media: String?,
    @field:NotBlank val password: String
)

data class PasswordRequest(
    @field:NotBlank val password: String
)

data class CommentCreateRequest(
    @field:Size(max = 50) val author: String?,
    @field:NotBlank val content: String
)

// ===== 응답 =====
data class CommentResponse(
    val id: Long,
    val author: String,
    val content: String,
    val createdAt: Instant
)

data class PostSummaryResponse(
    val id: Long,
    val category: String,
    val title: String,
    val author: String,
    val media: String?,
    val likes: Int,
    val views: Int,
    val commentCount: Int,
    val createdAt: Instant
)

data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalPages: Int,
    val totalElements: Long
)

data class PostDetailResponse(
    val id: Long,
    val category: String,
    val title: String,
    val author: String,
    val content: String,
    val media: String?,
    val likes: Int,
    val views: Int,
    val createdAt: Instant,
    val comments: List<CommentResponse>
)

fun Post.toSummary() = PostSummaryResponse(
    id, category, title, author, media, likes, views, comments.size, createdAt
)

fun Post.toDetail() = PostDetailResponse(
    id, category, title, author, content, media, likes, views, createdAt,
    comments.map { CommentResponse(it.id, it.author, it.content, it.createdAt) }
)
