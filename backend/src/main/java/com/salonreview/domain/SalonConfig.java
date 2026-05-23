package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "salon_config")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SalonConfig {

    @Id
    private Integer id;

    @Column(name = "owner_short_name", nullable = false)
    private String ownerShortName;
}
