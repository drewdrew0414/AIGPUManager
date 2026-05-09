package com.drewdrew1.server;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Checks optional PostgreSQL and Redis backends configured by environment variables. */
public class StorageHealthProbe {
    public Health check() {
        String postgres = checkPostgres();
        String redis = checkRedis();
        boolean ok = !postgres.startsWith("error") && !redis.startsWith("error");
        return new Health(ok, postgres, redis, "optional backends are controlled by GPUM_POSTGRES_URL and GPUM_REDIS_URL");
    }

    private String checkPostgres() {
        String url = System.getenv("GPUM_POSTGRES_URL");
        if (url == null || url.isBlank()) {
            return "disabled";
        }
        try (Connection connection = DriverManager.getConnection(
                url,
                System.getenv("GPUM_POSTGRES_USER"),
                System.getenv("GPUM_POSTGRES_PASSWORD"));
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select 1")) {
            return rs.next() ? "ok" : "empty";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String checkRedis() {
        String url = System.getenv("GPUM_REDIS_URL");
        if (url == null || url.isBlank()) {
            return "disabled";
        }
        try (Jedis jedis = new Jedis(url)) {
            return "PONG".equalsIgnoreCase(jedis.ping()) ? "ok" : "unexpected";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    public LockResult acquireRedisLock(String key, String owner, long ttlMillis) {
        String url = System.getenv("GPUM_REDIS_URL");
        if (url == null || url.isBlank()) {
            return new LockResult(false, "redis disabled");
        }
        try (Jedis jedis = new Jedis(url)) {
            String result = jedis.set("gpum:lock:" + key, owner, SetParams.setParams().nx().px(ttlMillis));
            return new LockResult("OK".equalsIgnoreCase(result), result == null ? "already locked" : result);
        } catch (Exception e) {
            return new LockResult(false, e.getMessage());
        }
    }

    public LockResult releaseRedisLock(String key, String owner) {
        String url = System.getenv("GPUM_REDIS_URL");
        if (url == null || url.isBlank()) {
            return new LockResult(false, "redis disabled");
        }
        try (Jedis jedis = new Jedis(url)) {
            String redisKey = "gpum:lock:" + key;
            String current = jedis.get(redisKey);
            if (owner.equals(current)) {
                jedis.del(redisKey);
                return new LockResult(true, "released");
            }
            return new LockResult(false, "owner mismatch");
        } catch (Exception e) {
            return new LockResult(false, e.getMessage());
        }
    }

    public record Health(boolean ok, String postgresStatus, String redisStatus, String detail) {
    }

    public record LockResult(boolean acquired, String detail) {
    }
}
