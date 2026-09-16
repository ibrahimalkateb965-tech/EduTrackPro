from psycopg.types.json import Jsonb


def write_audit(conn, actor_user_id, action, entity, entity_id, details: dict) -> None:
    conn.execute(
        "INSERT INTO audit_log (actor_user_id, action, entity, entity_id, details_json, at) "
        "VALUES (%s, %s, %s, %s, %s, now())",
        (actor_user_id, action, entity, entity_id, Jsonb(details)),
    )
