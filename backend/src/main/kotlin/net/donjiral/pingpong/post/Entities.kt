package net.donjiral.pingpong.post

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "posts")
class Post(
    // 게시판 구분 (예: pingpong, dev). 기존 데이터는 null → pingpong 취급
    @Column(length = 20)
    var board: String? = "pingpong",

    @Column(nullable = false, length = 30)
    var category: String,

    @Column(nullable = false, length = 200)
    var title: String,

    @Column(nullable = false, length = 50)
    var author: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    var content: String,

    @Column(length = 500)
    var media: String? = null,

    // 수정/삭제용 비밀번호 BCrypt 해시
    @Column(nullable = false)
    var passwordHash: String,

    @Column(nullable = false)
    var likes: Int = 0,

    @Column(nullable = false)
    var views: Int = 0,

    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),

    @OneToMany(mappedBy = "post", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("createdAt ASC")
    var comments: MutableList<Comment> = mutableListOf()
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
}

@Entity
@Table(name = "comments")
class Comment(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    var post: Post,

    @Column(nullable = false, length = 50)
    var author: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    var content: String,

    @Column(nullable = false)
    var createdAt: Instant = Instant.now()
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
}
