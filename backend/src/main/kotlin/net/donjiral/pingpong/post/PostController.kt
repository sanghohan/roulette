package net.donjiral.pingpong.post

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/posts")
class PostController(
    private val postService: PostService
) {
    // 목록 (카테고리/검색)
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "pingpong") board: String,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int
    ): PageResponse<PostSummaryResponse> = postService.list(board, category, q, page, size)

    // 상세 (조회수 +1)
    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): PostDetailResponse = postService.get(id)

    // 작성
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: PostCreateRequest): PostDetailResponse =
        postService.create(req)

    // 수정 (비밀번호 필요)
    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody req: PostUpdateRequest): PostDetailResponse =
        postService.update(id, req)

    // 삭제 (비밀번호 필요)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long, @Valid @RequestBody req: PasswordRequest) =
        postService.delete(id, req.password)

    // 좋아요
    @PostMapping("/{id}/like")
    fun like(@PathVariable id: Long): Map<String, Int> = mapOf("likes" to postService.like(id))

    // 댓글 작성
    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    fun addComment(@PathVariable id: Long, @Valid @RequestBody req: CommentCreateRequest): CommentResponse =
        postService.addComment(id, req)
}

@RestController
class HealthController {
    @GetMapping("/api/health")
    fun health() = mapOf("status" to "ok")
}
