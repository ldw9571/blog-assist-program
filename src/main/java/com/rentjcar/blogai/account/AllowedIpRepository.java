package com.rentjcar.blogai.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AllowedIpRepository extends JpaRepository<AllowedIp, Long> {
    boolean existsByIp(String ip);
    List<AllowedIp> findAllByOrderByIdDesc();
}
