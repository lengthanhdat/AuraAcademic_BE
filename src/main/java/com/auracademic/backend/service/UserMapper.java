package com.auracademic.backend.service;

import com.auracademic.backend.dto.UserProfileResponse;
import com.auracademic.backend.model.User;
import org.springframework.stereotype.Component;

/**
 * Stateless mapper — tránh circular dependency giữa AuthService và UserService.
 */
@Component
public class UserMapper {

    public UserProfileResponse toProfile(User user) {
        UserProfileResponse res = new UserProfileResponse();
        res.setId(user.getId());
        res.setFullName(user.getFullName());
        res.setEmail(user.getEmail());
        res.setRole(user.getRole());
        res.setStudentId(user.getStudentId());
        res.setPhoneNumber(user.getPhoneNumber());
        res.setBirthDate(user.getBirthDate());
        res.setGender(user.getGender());
        res.setTitle(user.getTitle());
        res.setDepartment(user.getDepartment());
        res.setWorkplace(user.getWorkplace());
        res.setSchedule(user.getSchedule());
        res.setAvatarUrl(user.getAvatarUrl());
        res.setBio(user.getBio());
        res.setCertificates(user.getCertificates());
        res.setExperience(user.getExperience());
        res.setProvider(user.getProvider());
        res.setHasPassword(user.getPassword() != null && !user.getPassword().isBlank());
        res.setEmailVerified(user.isEmailVerified());
        res.setTwoFactorEnabled(user.isTwoFactorEnabled());
        res.setCreatedAt(user.getCreatedAt());
        res.setLastLoginAt(user.getLastLoginAt());
        res.setFavoritePracticeIds(user.getFavoritePracticeIds() != null ? user.getFavoritePracticeIds() : new java.util.ArrayList<>());
        return res;
    }
}
