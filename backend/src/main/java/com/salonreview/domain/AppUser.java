package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * A login account. Authentication is via Spring Security against {@link #passwordHash} (bcrypt);
 * {@link #role} drives authorization. PROVIDER accounts carry {@link #providerId}, linking the login
 * to the provider person whose settlement they may view. ({@code created_at} is DB-managed.)
 */
@Entity
@Table(name = "app_user")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /** Set only for PROVIDER accounts: the provider person this login may view. */
    @Column(name = "provider_id")
    private Long providerId;

    /** Optional link to the Square team member this account came from (any role). */
    @Column(name = "square_team_member_id")
    private String squareTeamMemberId;

    @Column
    private String email;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
