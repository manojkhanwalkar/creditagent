package com.creditagent.web.auth;

import java.util.Optional;

public interface UserRepository {

    /** Stores the user unless the id is taken. @return false if a user with this id already exists */
    boolean createIfAbsent(User user);

    Optional<User> find(String userId);
}
