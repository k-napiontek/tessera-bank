/**
 * Use cases: what the ledger can be asked to do, composed from the domain and the ports.
 *
 * <p>Each class here is one operation of the API contract, and holds the sequencing that no single
 * aggregate can: take the transaction, lock the accounts in a safe order, ask the domain to decide,
 * write the result. That sequencing is the part of a ledger most easily got wrong, so it lives in
 * classes that can be driven by fakes in milliseconds rather than in a controller that needs a
 * database and an HTTP request to exercise.
 *
 * <p>Nothing here mentions HTTP, JSON, SQL or a framework - the same constraint the {@code port}
 * package works under, enforced by {@code DomainPurityTest} over this whole module.
 */
package bank.tessera.ledger.application;
