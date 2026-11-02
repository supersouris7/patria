/**
 * Socle HTTP sans framework. Le {@link fr.patria.web.Router} applique authentification, CSRF, en-tetes de securite
 * et pages d'erreur ; les controleurs recoivent une {@link fr.patria.web.Request} et renvoient une
 * {@link fr.patria.web.Response}, sans jamais manipuler le serveur HTTP directement.
 */
package fr.patria.web;
