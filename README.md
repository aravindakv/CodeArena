# CodeArena
CodeArena is a LeetCode-style Platformfor coding practice and fest platform built as a set of Spring Boot services behind one gateway.
User code never runs inside a service: it is queued and executed by a separate judge in throw-away, gVisor-isolated
containers.
