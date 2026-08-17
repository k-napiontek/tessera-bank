/**
 * Ports: what the domain needs from the outside world, expressed as interfaces it owns.
 *
 * <p>The domain declares these; it does not implement them and does not know what does. WP-07
 * supplies PostgreSQL adapters. Nothing here mentions SQL, a connection, a transaction manager or a
 * framework annotation, because the moment it does, the domain stops being testable in milliseconds
 * and starts being testable only against a database.
 *
 * <p>The direction of the dependency is the whole point: infrastructure depends on the domain, never
 * the reverse.
 */
package bank.tessera.ledger.port;
