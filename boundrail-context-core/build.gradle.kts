plugins {
    id("boundrail.java-conventions")
    id("boundrail.multi-release")
    `java-library`
    id("boundrail.published")
}

// The Contents cell of design.md §12 (REL-04).
description = "Keys, snapshots, scopes, policy, codecs, destination classification, deadline and identity types, both backends; zero runtime dependencies"
