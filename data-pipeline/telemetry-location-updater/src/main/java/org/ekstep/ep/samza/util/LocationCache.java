package org.ekstep.ep.samza.util;

import com.datastax.driver.core.Row;
import org.apache.samza.config.Config;
import org.ekstep.ep.samza.core.Logger;
import org.ekstep.ep.samza.domain.Location;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LocationCache {

    private static Logger LOGGER = new Logger(LocationCache.class);

    private String cassandra_db;
    private String cassandra_table;
    private CassandraConnect cassandraConnetion;
    private RedisConnect redisConnect;
    private int locationDbKeyExpiryTimeInSeconds;

    public LocationCache(Config config, RedisConnect redisConnect) {
        this.cassandra_db = config.get("cassandra.keyspace", "device_db");
        this.cassandra_table = config.get("cassandra.device_profile_table", "device_profile");
        this.redisConnect = redisConnect;
        this.cassandraConnetion = new CassandraConnect(config);
        this.locationDbKeyExpiryTimeInSeconds = config.getInt("location.db.redis.key.expiry.seconds", 86400);
    }

    public Location getLocationForDeviceId(String did, String channel) {

        try (Jedis jedis = redisConnect.getConnection()) {
            // Key will be device_id:channel
            String key = String.format("%s:%s", did, channel);
            Map<String, String> fields = jedis.hgetAll(key);
            List<Row> rows;
            if (fields.isEmpty()) {
                String query =
                    String.format("SELECT device_id, country_code, country, state_code, state, city FROM %s.%s WHERE device_id = '%s' AND channel = '%s'",
                        cassandra_db, cassandra_table, did, channel);
                rows = cassandraConnetion.execute(query);
                String countryCode = null;
                String country = null;
                String stateCode = null;
                String state = null;
                String city = null;

                if (rows.size() > 0) {
                    Row row = rows.get(0);
                    countryCode = row.getString("country_code");
                    country = row.getString("country");
                    stateCode = row.getString("state_code");
                    state = row.getString("state");
                    city = row.getString("city");
                }

                Location location = new Location(countryCode, country, stateCode, state, city);
                if (location.isLocationResolved()) {
                    addLocationToCache(did, channel, location);
                    return location;
                } else return null;
            } else {
                return new Location(fields.get("country_code"), fields.get("country"),
                        fields.get("state_code"), fields.get("state"), fields.get("city"));
            }
        } catch (JedisException ex) {
            LOGGER.error("", "GetLocationForDeviceId: Unable to get a resource from the redis connection pool ", ex);
            return null;
        }
    }

    public void addLocationToCache(String did, String channel, Location location) {
        try (Jedis jedis = redisConnect.getConnection()) {
            if(location.isLocationResolved()) {
                // Key will be device_id:channel
                String key = String.format("%s:%s", did, channel);
                Map<String, String> values = new HashMap<>();
                values.put("country_code", location.getCountryCode());
                values.put("country", location.getCountry());
                values.put("state_code", location.getStateCode());
                values.put("state", location.getState());
                values.put("city", location.getCity());
                jedis.hmset(key, values);
                jedis.expire(key, locationDbKeyExpiryTimeInSeconds);
            }
        } catch (JedisException ex) {
            LOGGER.error("", "AddLocationToCache: Unable to get a resource from the redis connection pool ", ex);
        }
    }
}
