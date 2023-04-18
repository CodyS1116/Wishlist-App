package edu.sunyulster.genie.services;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static edu.sunyulster.genie.utils.Validator.isEmailValid;
import static edu.sunyulster.genie.utils.Validator.isWishlistValid;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

import edu.sunyulster.genie.exceptions.InvalidDataException;
import edu.sunyulster.genie.models.Wishlist;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.security.enterprise.AuthenticationException;
import jakarta.ws.rs.ForbiddenException;


@ApplicationScoped
public class WishlistService {
    @Inject
    MongoDatabase db;

    public Wishlist create(String userId, Wishlist w) throws InvalidDataException, AuthenticationException {
        // get user's email - change to storing user id  or name later?
        MongoCollection<Document> users = db.getCollection("users");
        Document match = users.find(eq("authId", new ObjectId(userId))).first();
        if (match == null)
            throw new AuthenticationException("User does not exist!");
        // String name = match.getString("firstName") + " " + match.getString("lastName");    
        String userEmail = match.getString("email");

        // validate information
        if (!isWishlistValid(w)) 
            throw new InvalidDataException("Wishlist must have a name");

        // create wishlist
        Document newWishlist = new Document()
            .append("_id", new ObjectId())
            .append("name", w.getName())
            .append("items", new ArrayList<ObjectId>())
            .append("sharedWith", new ArrayList<String>())
            .append("dateCreated", new Date())
            .append("owner", userEmail);

        // add to wishlist collection
        MongoCollection<Document> wishlists = db.getCollection("wishlists");
        wishlists.insertOne(newWishlist);

        // add wishlist id to user
        Bson update = Updates.addToSet("wishlists", newWishlist.getObjectId("_id"));
        users.updateOne(eq("authId", new ObjectId(userId)), update);

        return documentToWishlist(newWishlist);
    }

    public List<Wishlist> getAll(String userId) throws AuthenticationException {
        // get all wishlists for the user 
        MongoCollection<Document> users = db.getCollection("users");
        Document match = users.find(eq("authId", new ObjectId((userId)))).first();
        // occurs if user is deleted but still has valid jwt for example
        if (match == null)
            throw new AuthenticationException("User does not exist!");

        List<ObjectId> wishlistIds = (ArrayList<ObjectId>) match.get("wishlists");
        MongoCollection<Document> wishlistCol = db.getCollection("wishlists");
        FindIterable<Document> matchingWishlists = wishlistCol.find(in("_id", wishlistIds))
            .sort(Sorts.ascending("dateCreated"));

        List<Wishlist> wishlists = new ArrayList<>(wishlistIds.size());
        for (Document w : matchingWishlists) 
            wishlists.add(documentToWishlist(w));
        
        return wishlists;
    }

    public Wishlist get(String userId, String id, boolean isOwner) {
        ObjectId wishlistId = new ObjectId(id);
        if (isOwner)
            checkWishlistOwnership(new ObjectId(userId), wishlistId);
        else {
            // see if the user has been added to the wishlist
            MongoCollection<Document> users = db.getCollection("users");
            Document match = users.find(Filters.and(eq("authId", new ObjectId(userId)), in("sharedWishlists", wishlistId))).first();
            if (match == null)
                throw new ForbiddenException("User has not been added to this wishlist");
        }

        MongoCollection<Document> wishlists = db.getCollection("wishlists");
        Document match = wishlists.find(eq("_id", wishlistId)).first();
        if (match==null){
            System.out.println("\n\n\n\n\n-----------------------MATCH--------------------");        
            System.out.println(match);
            System.out.println("-------------------------------\n\n\n\n\n");
        }
        System.out.println(match);
        if (match == null) 
            throw new NoSuchElementException("Wishlist does not exist");
        return documentToWishlist(match);
    }

    public Wishlist update(String userId, Wishlist newWishlist) throws InvalidDataException {
        ObjectId wishlistId = new ObjectId(newWishlist.getId());
        checkWishlistOwnership(new ObjectId(userId), wishlistId);
        
        MongoCollection<Document> wishlists = db.getCollection("wishlists");
        Bson filter = Filters.eq("_id", new ObjectId(newWishlist.getId()));
        Bson update = null;
            
         // validate information
         if (isWishlistValid(newWishlist)) {
            update = Updates.set("name", newWishlist.getName());
         }

        if (newWishlist.getSharedWith()!=null && newWishlist.getSharedWith().size()>0 && isEmailValid(newWishlist.getSharedWith().get(0)) && isEmailReal(newWishlist.getSharedWith().get(0))) {
            Bson emailUpdate = Updates.addToSet("sharedWith", newWishlist.getSharedWith().get(0));
            update = update == null ? emailUpdate : Updates.combine(emailUpdate, update);
            
            //update user with new wishlist
            addWishlistToUser(newWishlist.getId(), newWishlist.getSharedWith().get(0));
        }

        //combine updates
        if (update != null) {
            wishlists.updateOne(filter, update);
        }

        return documentToWishlist(wishlists.find(eq("_id", wishlistId)).first());
    }

    public void delete(String userId, String id) {
        ObjectId wishlistId = new ObjectId(id);
        checkWishlistOwnership(new ObjectId(userId), wishlistId);

        // delete all items from wishlist
        MongoCollection<Document> wishlists = db.getCollection("wishlists");
        MongoCollection<Document> items = db.getCollection("items");
        List<ObjectId> itemsToDelete = (List<ObjectId>) wishlists.find(eq("_id", wishlistId)).first().get("items");
        items.deleteMany(in("_id", itemsToDelete));
        
        // delete wishlist
        wishlists.deleteOne(eq("_id", wishlistId));

        // delete wishlist object id from user
        MongoCollection<Document> users = db.getCollection("users");
        users.updateOne(eq("authId", new ObjectId(userId)), Updates.pull("wishlists", wishlistId));
    }

    private Wishlist documentToWishlist(Document d) {
        int itemCount = ((ArrayList<ObjectId>) d.get("items")).size();
        Wishlist list =  new Wishlist(
            d.getString("owner"),
            d.getObjectId("_id").toString(), 
            d.getString("name"), 
            itemCount, 
            d.getDate("dateCreated"));
        list.setSharedWith((List<String>) d.get("sharedWith"));
        return list;
    }

    private Document checkWishlistOwnership(ObjectId userId, ObjectId wishlistId) {
        MongoCollection<Document> users = db.getCollection("users");
        Document match = users.find(eq("authId", userId)).first();
        List<ObjectId> wishlistIds = (ArrayList<ObjectId>) match.get("wishlists");
        for (ObjectId i : wishlistIds) {
            if (i.equals(wishlistId))
                return match;
        }
        throw new ForbiddenException("Wishlist does not belong to requesting user");
    }

    private boolean isEmailReal(String email) {
        MongoCollection<Document> users=db.getCollection("users");
        Document theOne=users.find(eq("email", email)).first();
        if (theOne==null) 
            return false;
        else    
            return true;
    }

    private void addWishlistToUser(String wishlistId, String userEmail) {
        //get user with userEmail
        MongoCollection<Document> users = db.getCollection("users");
        Bson filter = Filters.eq("email", userEmail);
        Bson update = Updates.addToSet("sharedWishlists", new ObjectId(wishlistId));
        users.updateOne(filter, update);
    }
}
