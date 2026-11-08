/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.graphql.modelutil;

import com.facebook.graphservice.tree.TreeJNI;

import java.util.HashMap;
import java.util.Map;

/**
 * A test stand-in for the base of Facebook's GraphQL models. The real one reads a field out of a
 * native tree by the hash of the field's GraphQL name, and its type name out of the same tree.
 * These members are the ones the extension calls, under the names Facebook keeps: the GenAI rule
 * reads a flag through the public {@code getCachedBoolean(int)} by reflection, and the video save
 * reads strings and the type name, and the comment filter a comment's body through
 * {@code getCachedModel(int, Class, int)}. Like Facebook's, a boolean key the model doesn't hold reads
 * false. Every boolean read is counted, so a test can show that a switched-off rule reads nothing.
 * The tree accessors the file name uses are the ones every tree has, on {@link TreeJNI}.
 */
public class BaseModelWithTree extends TreeJNI {
    private final Map<Integer, Boolean> booleans = new HashMap<>();
    private final Map<Integer, BaseModelWithTree> models = new HashMap<>();
    private final String typeName;
    public int reads;

    /** A model tagged with a type's hash, as the GenAI rule checks it. */
    public BaseModelWithTree(int typeTag) {
        this(typeTag, null);
    }

    /** A model with a GraphQL type name, as the video save reads it. */
    protected BaseModelWithTree(String typeName) {
        this(0, typeName);
    }

    private BaseModelWithTree(int typeTag, String typeName) {
        super(typeTag);
        this.typeName = typeName;
    }

    public BaseModelWithTree with(String field, boolean value) {
        booleans.put(field.hashCode(), value);
        return this;
    }

    /** Sets the string field named [field], the way a fetched tree holds it. */
    @Override
    public BaseModelWithTree with(String field, String value) {
        super.with(field, value);
        return this;
    }

    /** A model whose native tree is gone, as after Facebook releases it. */
    public BaseModelWithTree released() {
        releasedTree();
        return this;
    }

    public final boolean getCachedBoolean(int key) {
        reads++;
        Boolean value = booleans.get(key);
        return value != null && value;
    }

    /**
     * The model's own cache, one slot per field hash, as Facebook's {@code getFromCache} and
     * {@code putToCache} keep it. A read keeps what it read there, and the next read of that field,
     * of any kind, casts whatever the slot holds, so a String left where an enum belongs throws
     * ClassCastException, as it does on a phone.
     */
    private final Map<Integer, Object> cache = new HashMap<>();

    /** The model's own cache of a string field: no native read, so safe on a released model. */
    public final String getCachedString(int field) {
        Object kept = cache.get(field);
        if (kept != null) return (String) kept;
        String value = cachedString(field);
        if (value != null) cache.put(field, value);
        return value;
    }

    /**
     * An enum field, as Facebook's kept {@code getCachedEnum(int, Class, Enum)} answers it: what
     * the cache holds, cast, or the tree's string as the enum of [fallback]'s class, kept in the
     * cache, and [fallback] when no constant has that name.
     */
    public final Enum<?> getCachedEnum(int field, Class<?> type, Enum<?> fallback) {
        Object kept = cache.get(field);
        if (kept != null) return (Enum<?>) kept;
        String value = cachedString(field);
        Enum<?> found = fallback;
        for (Object constant : fallback.getDeclaringClass().getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(value)) found = (Enum<?>) constant;
        }
        cache.put(field, found);
        return found;
    }

    /** Sets the model field named [field], as getCachedModel answers it. */
    public BaseModelWithTree withModel(String field, BaseModelWithTree model) {
        models.put(field.hashCode(), model);
        return this;
    }

    /**
     * A model field, as Facebook's kept {@code getCachedModel(int, Class, int)} answers it: the
     * model under the field name's hash when it's of the class and type tag asked for, else null.
     */
    public final BaseModelWithTree getCachedModel(int field, Class<?> type, int typeTag) {
        BaseModelWithTree model = models.get(field);
        return model != null && type.isInstance(model) && model.mTypeTag == typeTag ? model : null;
    }

    /** The model's int field, as Facebook's public {@code getCachedInt(int)} answers it; an unset one reads 0. */
    public final int getCachedInt(int key) {
        return getIntValue(key);
    }

    public String getTypeName() {
        if (!isValidGraphServicesJNIModel()) readAfterRelease = true;
        return typeName;
    }
}
