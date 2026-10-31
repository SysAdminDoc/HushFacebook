package com.facebook.graphql.model;

import com.facebook.graphql.modelutil.BaseModelWithTree;

/** The person who wrote a comment, with the id and name a fetched tree holds. */
public class GraphQLActor extends BaseModelWithTree {
    public GraphQLActor(String id, String name) {
        super(0);
        if (id != null) with("id", id);
        if (name != null) with("name", name);
    }
}
