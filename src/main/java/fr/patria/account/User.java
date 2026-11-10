// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.account;

/** Utilisateur enregistre en base (l'administrateur, lui, vient de l'environnement). */
public record User(long id, String login, String name, boolean active) {
}
