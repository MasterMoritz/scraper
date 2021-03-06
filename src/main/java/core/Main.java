package core;

import static spark.Spark.*;

import java.net.URL;
import java.util.List;
import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.lang.Thread;
import java.lang.StringBuilder;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParseException;
import org.apache.commons.io.IOUtils;
import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.eclipsesource.json.JsonArray;

import core.Database;
import core.Event;
import core.EventFactory;
import core.Restaurant;
import core.Endpoints;

import java.lang.Math;

public class Main {
	private static final Integer DEBUG = 0;

    public static void main(String[] args) {
        port(getHerokuAssignedPort());

		//Tell spark where our static files are
		staticFileLocation("/public");

		//update events every 2 hours
		ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
		exec.scheduleAtFixedRate(new Runnable() {
		  public void run() {
			updateEventDatabase();
		  }
		}, 0, 2, TimeUnit.HOURS);

		//update restaurants every 5 days
		long delay = 0;
		if (Database.existsRestaurantDatabase()) {
			// delay update if database exists already <- updating restaurants is super slow
			delay = 5; 
		}
		exec.scheduleAtFixedRate(new Runnable() {
		  public void run() {
			updateRestaurantDatabase();
		  }
		}, delay, 5, TimeUnit.DAYS);

		//--- ROUTES

        get("/hello", (req, res) -> "https://www.youtube.com/watch?v=Am4oKAmc2To");

		/* GET - our API endpoints
			:type - either 'event' or 'restaurant' or 'all'

			possible extensions not yet implemented
			:filter - unused (may be used for 'artist' and 'location', etc.)
			:filtertext - unused(text that should be filtered for the set filter)
		*/
		get("/API/:type", "application/json", (request, response) -> {
			String type = request.params(":type");
			StringBuilder sb = new StringBuilder("{\"status\": ");
			String status = "\"success\"";
			List<Event> events = new ArrayList<Event>();
			List<Restaurant> restaurants = new ArrayList<Restaurant>();
			ObjectMapper mapper = new ObjectMapper();

			if (type.equals("all")) {
				events = Database.getAllEvents();
				restaurants = Database.getAllRestaurants();
			}
			else if (type.equals("event")) {
				//could extend with filter options here
				events = Database.getAllEvents();
			}
			else if (type.equals("restaurant")) {
				//could extend with filter options here
				restaurants = Database.getAllRestaurants();	
			}
			else {
				status = "\"error\"";
			}

			sb.append(status + ", \"response\": {\"events\": ");
			StringJoiner sj = new StringJoiner(",", "[", "]");
			for (Event e : events) {
				sj.add(mapper.writeValueAsString(e));
			}
			sb.append(sj.toString() + ", \"restaurants\": ");
			sj = new StringJoiner(",", "[", "]");
			for (Restaurant r : restaurants) {
				sj.add(mapper.writeValueAsString(r));
			}
			sb.append(sj.toString() + "}}");
			return sb.toString();
		});

		/* POST - /find
		 * returns all events/restaurants in the circumference of a specific restaurant/event
		 * (depending on post param) as json
		*/
		post("/find", "application/json",(req, res) -> {
			JsonObject request = Json.parse(req.body()).asObject();

			//get the request type (1 = Events, 2 = restaurants, Default = 1)
			Integer type = Integer.parseInt(request.getString("type","1"));

			//get the id of the specific event/restaurant (-x = error)
			Long id = Long.parseLong(request.getString("id","-1"));
			if (id < 0) {
				return ""; //replace with badrequest or something
			}

			//get the search radius (Default = 1.0)
			Float radius = Float.parseFloat(request.getString("radius","1.0"));
			radius *= 1000; //convert from km to m

			//return events or restaurants depending on type/id and search radius
			StringJoiner sj = new StringJoiner(",", "[", "]");
			ObjectMapper mapper = new ObjectMapper();

			//find nearby restaurants
			if (type == 1) {
				Event event = Database.getAllEvents("WHERE e.id IN ("+id+")").get(0); // write an extra method for that in database class
				List<Restaurant> restaurants;
				restaurants = Database.getAllRestaurants();

				for (Restaurant r : restaurants) {
					if (inCircumference(event.getLatitude(),event.getLongitude(), r.getLatitude(), r.getLongitude(),radius)) {
						sj.add(mapper.writeValueAsString(r));
					}
				}
			}
			//find nearby events
			else if (type == 2) {
				Restaurant restaurant = Database.getAllRestaurants("WHERE r.id IN ("+id+")").get(0); // write an extra method for that in database class
				List<Event> events;
				events = Database.getAllEvents();

				for (Event e : events) {
					if (inCircumference(e.getLatitude(),e.getLongitude(), restaurant.getLatitude(), restaurant.getLongitude(),radius)) {
						sj.add(mapper.writeValueAsString(e));
					}
				}
			}

			return sj.toString();
		});

		/* POST - /search
		 * returns all events/restaurants (depending on post param) as json
		*/
		post("/search", "application/json",(req, res) -> {
			JsonObject request = Json.parse(req.body()).asObject();

			//get the request type (1 = Events, 2 = restaurants, Default = 1)
			Integer type = Integer.parseInt(request.getString("type","1"));

			//get the search term or "" if empty
			String text = request.getString("text","");

			//return events or restaurants depending on type and search text
			StringJoiner sj = new StringJoiner(",", "[", "]");
			ObjectMapper mapper = new ObjectMapper();

			if (type == 1) {
				List<Event> events;
				if (text.isEmpty()) {
					events = Database.getAllEvents();
				}
				else {
					events = Database.getAllEvents("WHERE LOWER(e.name) LIKE LOWER('%"+text+"%')");
				}
				for (Event e : events) {
					sj.add(mapper.writeValueAsString(e));
				}
			}
			else if (type == 2) {
				List<Restaurant> restaurants;
				if (text.isEmpty()) {
					restaurants = Database.getAllRestaurants();
				}
				else {
					restaurants = Database.getAllRestaurants("WHERE LOWER(r.name) LIKE LOWER('%"+text+"%')");
				}
				for (Restaurant r : restaurants) {
					sj.add(mapper.writeValueAsString(r));
				}
			}

			return sj.toString();
		});
    }

    static int getHerokuAssignedPort() {
        ProcessBuilder processBuilder = new ProcessBuilder();
        if (processBuilder.environment().get("PORT") != null) {
            return Integer.parseInt(processBuilder.environment().get("PORT"));
        }
        return 4567; //return default port if heroku-port isn't set (i.e. on localhost)
    }

	
	//update the database with api results
	static void updateDatabase() {
		updateEventDatabase();
		updateRestaurantDatabase();
	}

	//update the database with restaurant api results
	static void updateRestaurantDatabase() {

		ArrayList<Restaurant> restaurants = new ArrayList<>(600);
		ArrayList<Restaurant> temp_restaurants = new ArrayList<>(600); //no geocoding yet
		RestaurantFactory restaurant_factory = new RestaurantFactory();
	
		
		//--- Check if geocodinging worked and if restaurant was already added by a different API (the latter is probably superfluous)---
		Consumer<Restaurant> addRestaurant = (r) -> {
			//within 30km of Innsbruck -> to eliminate geocoding errors
			if(inCircumference(r.getLatitude(), r.getLongitude(), 47.259659f, 11.400375f, 30000f)) {
				for(Restaurant c : restaurants) {
					if(c.getLatitude() == r.getLatitude() && c.getLongitude() == r.getLongitude())
						return;
				}
				restaurants.add(r);
			}
		};

		//-- yelp API --
		System.out.println("Parsing Yelp API");
		try {

			// had to split it up into 3 parts because Kimono can not return more than 2500 rows
			
			//create json object from url
			URL endpoint_basics = new URL(Endpoints.yelpBasics1);
			String endpoint_basics_content = IOUtils.toString(endpoint_basics, "UTF-8");
			JsonObject json_basics_1 = Json.parse(endpoint_basics_content).asObject();

			//create json object from url
			URL endpoint_oh = new URL(Endpoints.yelpOpeningHours1);
			String endpoint_oh_content = IOUtils.toString(endpoint_oh, "UTF-8");
			JsonObject json_oh_1 = Json.parse(endpoint_oh_content).asObject();
			
			endpoint_basics = new URL(Endpoints.yelpBasics2);
			endpoint_basics_content = IOUtils.toString(endpoint_basics, "UTF-8");
			JsonObject json_basics_2 = Json.parse(endpoint_basics_content).asObject();
			
			//create json object from url
			endpoint_oh = new URL(Endpoints.yelpOpeningHours2);
			endpoint_oh_content = IOUtils.toString(endpoint_oh, "UTF-8");
			JsonObject json_oh_2 = Json.parse(endpoint_oh_content).asObject();
			
			endpoint_basics = new URL(Endpoints.yelpBasics3);
			endpoint_basics_content = IOUtils.toString(endpoint_basics, "UTF-8");
			JsonObject json_basics_3 = Json.parse(endpoint_basics_content).asObject();
			
			//create json object from url
			endpoint_oh = new URL(Endpoints.yelpOpeningHours3);
			endpoint_oh_content = IOUtils.toString(endpoint_oh, "UTF-8");
			JsonObject json_oh_3 = Json.parse(endpoint_oh_content).asObject();
			
			// had to split it up into 3 parts because Kimono can not return more than 2500 rows
			YelpParser.parseFromAPIs(json_basics_1, json_oh_1, temp_restaurants);
			System.out.println("Done with parsing part 1");
			YelpParser.parseFromAPIs(json_basics_2, json_oh_2, temp_restaurants);
			System.out.println("Done with parsing part 2");
			YelpParser.parseFromAPIs(json_basics_3, json_oh_3, temp_restaurants);
			System.out.println("Done with parsing part 3");
		}catch (Exception e) {
			System.out.println("Exception: API Error with yelp call");
			if(DEBUG == 1) {e.printStackTrace();}
			return;
		}
		
		//System.out.println(restaurants.toString());
		
		//-- X API --
		
		//-- get restaurant latitude/longitude
		System.out.println("Geocoding latitude/longitude of restaurants!");
		try{
			for (Restaurant r : temp_restaurants) {
				//only query if necessary
				if (r.getLatitude() < 0.01) {
					String street = r.getStreet().replace(' ','+');
					URL endpoint = new URL(Endpoints.geocode + street + ",+6020+Innsbruck");
					String endpoint_content = IOUtils.toString(endpoint, "UTF-8");
					JsonObject json = Json.parse(endpoint_content).asObject();
					String status = json.get("status").asString();

					//geocode call successful, parse latitude/longitude
					if (status.equals("OK")) {
						JsonObject json_location = json.get("results").asArray().get(0).
									               asObject().get("geometry").asObject().get("location").asObject();
						r.setLatitude(json_location.get("lat").asFloat());
						r.setLongitude(json_location.get("lng").asFloat());
						// System.out.println("Restaurant: " + r.getName() + " Street: " + r.getStreet() + " Latitude: " + r.getLatitude() + " Longitude: " + r.getLongitude());
					}
					//hit query limit of 2500/day, can only query 10/s now
					else if (status.equals("OVER_QUERY_LIMIT")) {
						Thread.sleep(12000);
						endpoint = new URL(Endpoints.geocode + street + ",+6020+Innsbruck");
						endpoint_content = IOUtils.toString(endpoint, "UTF-8");
						json = Json.parse(endpoint_content).asObject();
						status = json.get("status").asString();
						if (status.equals("OK")) {
							JsonObject json_location = json.get("results").asArray().get(0).
												       asObject().get("geometry").asObject().get("location").asObject();
							r.setLatitude(json_location.get("lat").asFloat());
							r.setLongitude(json_location.get("lng").asFloat());
						}
						else continue;
					}
					else continue;
				}
				addRestaurant.accept(r);
			}
			
		}
		catch(Exception e){
			System.out.println("Exception: API Error with google geocoding");
			return;
		}

		System.out.println("done geocoding");

		//-- delete old database
		Database.wipeRestaurantDatabase();

		//-- Add Restaurants to Database
		Database.addAllRestaurants(restaurants);
		System.out.println("updated restaurant database!");
	}

	//update the database with event api results
	static void updateEventDatabase() {

		EventFactory event_factory = new EventFactory();
		ArrayList<Event> events = new ArrayList<>();
		
		//-- Check if event was already added by a different API --
		Consumer<Event> addEvent = (e) -> {
			//If there is a venue that can host more than one event at the same time, then we need to handle the check differently
			//(although I am not aware of one that is located in Innsbruck)
			for(Event c : events) {
				if(c.getVenue().equalsIgnoreCase(e.getVenue())) {
						if(c.getStartTime() != null && e.getStartTime() != null) {	//if possible, check for time
							if(c.getStartTime().equalsIgnoreCase(e.getStartTime()))
								return;
						}
						else {	//check for name not optimal, as they can differ depending on source API
							if(c.getName().equalsIgnoreCase(e.getName()))
								return;
							for(Artist a : c.getArtists()) {
								if(a.getName().equalsIgnoreCase(e.getName()))	//some APIs tend to use the artist name instead of the event name
									return;
							}
							for(Artist a : e.getArtists()) {
								if(a.getName().equalsIgnoreCase(c.getName()))	//some APIs tend to use the artist name instead of the event name
									return;
							}
						}
				}
			}
			events.add(e);
		};

		//-- 5gig API --
		System.out.println("Parsing 5gig API");
		try {
			//create json object from url
			URL endpoint = new URL(Endpoints.fivegig);
			String endpoint_content = IOUtils.toString(endpoint, "UTF-8");
			JsonObject json = Json.parse(endpoint_content).asObject();
			
			//check if 5gig api call was successful
			if (json.get("status").asString().equals("success")) {
				//parse 5gig response
				JsonArray json_events = json.get("response").asObject().get("gigs").asArray();
	
				for (JsonValue value : json_events) {
					addEvent.accept((event_factory.createGigEvent(value.asObject())));
				}
			}
			else {
				System.out.println("API Error with 5gig call");
				return;
			}
		}
		catch (Exception e) {
			if(DEBUG == 1) {e.printStackTrace();}
			System.out.println("Exception: API Error with 5gig call");
			return;
		}
		//-- Treibhaus API via Kimono --
		System.out.println("Parsing Treibhaus API");
		try {
			//create json object from url
			URL endpoint = new URL(Endpoints.treibhaus);
			String endpoint_content = IOUtils.toString(endpoint, "UTF-8");
			JsonObject json = Json.parse(endpoint_content).asObject();

			//check if treibhaus api call was successful
			if (json.get("thisversionstatus").asString().equals("success")) {
				//parse treibhaus response
				JsonArray json_events = json.get("results").asObject().get("collection1").asArray();

				for (JsonValue value : json_events) {
					addEvent.accept((event_factory.createTreibhausEvent(value.asObject())));
				}
			}
			else {
				System.out.println("API Error with Treibhaus call");
				return;
			}
		}
		catch (Exception e) {
			if(DEBUG == 1) {e.printStackTrace();}
			System.out.println("Exception: API Error with Treibhaus call");
			return;
		}

		//-- Hafen API via Kimono --
		System.out.println("Parsing Hafen API");
		try {
			//create json object from url
			URL endpoint = new URL(Endpoints.hafen);
			String endpoint_content = IOUtils.toString(endpoint, "UTF-8");
			JsonObject json = Json.parse(endpoint_content).asObject();

			//check if Hafen api call was successful
			if (json.get("thisversionstatus").asString().equals("success")) {
				//parse Hafen response
				JsonArray json_events = json.get("results").asObject().get("collection1").asArray();

				for (JsonValue value : json_events) {
					addEvent.accept((event_factory.createHafenEvent(value.asObject())));
				}
			}
			else {
				System.out.println("API Error with Hafen call");
				return;
			}
		}
		catch (Exception e) {
			if(DEBUG == 1) {e.printStackTrace();}
			System.out.println("Exception: API Error with Hafen call");
			return;
		}

		//-- Weekender API via Kimono --
		System.out.println("Parsing Weekender API");
		try {
			//create json object from url
			URL endpoint = new URL(Endpoints.weekender);
			String endpoint_content = IOUtils.toString(endpoint, "UTF-8");
			JsonObject json = Json.parse(endpoint_content).asObject();

			//check if Weekender api call was successful
			if (json.get("thisversionstatus").asString().equals("success")) {
				//parse Weekender response
				JsonArray json_events = json.get("results").asObject().get("collection1").asArray();

				for (JsonValue value : json_events) {
					addEvent.accept((event_factory.createWeekenderEvent(value.asObject())));
				}
			}
			else {
				System.out.println("API Error with Weekender call");
				return;
			}
		}
		catch (Exception e) {
			if(DEBUG == 1) {e.printStackTrace();}
			System.out.println("Exception: API Error with Weekender call");
			return;
		}

		//-- Events.at API via Kimono --
		// 2016-02-01: Martin: Results are very bad and also displayed very bad -> ignore events.at ...
		/*System.out.println("Parsing Events.at API");
		try {
			//create json object from url
			URL endpoint = new URL(Endpoints.eventsAt);
			String endpoint_content = IOUtils.toString(endpoint, "UTF-8");
			JsonObject json = Json.parse(endpoint_content).asObject();

			//check if events.at api call was successful
			if (json.get("thisversionstatus").asString().equals("success")) {
				//parse Events.at response
				JsonArray json_events = json.get("results").asObject().get("collection1").asArray();

				for (JsonValue value : json_events) {
					addEvent.accept((event_factory.createEventsAtEvent(value.asObject())));
				}
			}
			else {
				System.out.println("API Error with Events.at call");
				return;
			}
		}
		catch (Exception e) {
			if(DEBUG == 1) {e.printStackTrace();}
			System.out.println("Exception: API Error with Events.at call");
			return;
		}*/

		//-- delete old database
		Database.wipeEventDatabase();

		//-- add events to database
		Database.addAllEvents(events);
		System.out.println("updated event database!");

	}

	/** check if x2,y2 are in circumference of x1,y1 wrt. radius (in m)*/
	static boolean inCircumference(Float x1, Float y1, Float x2, Float y2, Float radius) {
		/*float x = x2 - x1;
		float y = y2 - y1;

		float length = (float)Math.sqrt(x*x + y*y);*/
		float length = (float) computeDistance(x1, y1, x2, y2);
		if (length > radius) {
			return false;
		}
		return true;
	}
	
	double rad(double x) {
		  return x * Math.PI / 180;
	};

	//distance calculation based on the Haversine formula
	static float computeDistance(Float x1, Float y1, Float x2, Float y2) {
	  int R = 6378137; // Earth�s mean radius in meter
	  double dLat = Math.toRadians(x2 - x1);
	  double dLong = Math.toRadians(y2 - y1);
	  double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
	    Math.cos(Math.toRadians(x1)) * Math.cos(Math.toRadians(x2)) *
	    Math.sin(dLong / 2) * Math.sin(dLong / 2);
	  double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
	  double d = R * c;
	  return (float)d; // returns the distance in meter
	};
}
