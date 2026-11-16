package com.exe.morphia3d.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ModelUrlResolver {

    @Value("${cloudinary.base-url}")
    private String baseUrl;

    public String resolve(String path) {

        if (path == null || path.isBlank())
            return "";

        if (path.startsWith("http"))
            return path;

        return baseUrl + "/" + path;
    }
}