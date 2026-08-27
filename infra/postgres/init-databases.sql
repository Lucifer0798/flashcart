-- One database per service, created at first container start.
--
-- Separate databases, not separate schemas in one: the point of the split is that no service can
-- reach into another's tables even by accident, and a shared database makes that a matter of
-- discipline rather than a matter of permissions. They share one PostgreSQL instance only because
-- running six of them on a laptop buys nothing — in production these are six instances.
--
-- Every service gets its database from day one, including the five whose schemas arrive in later
-- phases, so bringing a service to life is a code change and never an infrastructure change.

create database flashcart_catalog;
create database flashcart_order;
create database flashcart_user;
create database flashcart_payment;
create database flashcart_inventory;
create database flashcart_shipping;
