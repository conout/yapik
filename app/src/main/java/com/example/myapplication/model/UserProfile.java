package com.example.myapplication.model;

public final class UserProfile {

    private final String login;
    private final String avatarUrl;

    public UserProfile(String login, String avatarUrl) {
        this.login = login == null || login.trim().isEmpty() ? "Яндекс" : login;
        this.avatarUrl = avatarUrl;
    }

    public String getLogin() {
        return login;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }
}
