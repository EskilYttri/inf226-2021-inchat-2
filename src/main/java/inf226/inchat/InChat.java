package inf226.inchat;

import inf226.inchat.Account.Role;
import inf226.storage.DeletedException;
import inf226.storage.Stored;
import inf226.util.Maybe;
import inf226.util.Maybe.NothingException;
import inf226.util.Util;
import inf226.util.immutable.List;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * This class models the chat logic.
 *
 * It provides an abstract interface to
 * usual chat server actions.
 *
 **/

public class InChat {
    private final Connection connection;
    private final UserStorage userStore;
    private final ChannelStorage channelStore;
    private final EventStorage eventStore;
    private final AccountStorage accountStore;
    private final SessionStorage sessionStore;
    private final Map<UUID, List<Consumer<Channel.Event>>> eventCallbacks
            = new TreeMap<UUID, List<Consumer<Channel.Event>>>();

    public InChat(UserStorage userStore,
                  ChannelStorage channelStore,
                  AccountStorage accountStore,
                  SessionStorage sessionStore,
                  Connection connection) {
        this.userStore = userStore;
        this.channelStore = channelStore;
        this.eventStore = channelStore.eventStore;
        this.accountStore = accountStore;
        this.sessionStore = sessionStore;
        this.connection = connection;
        this.loginHandler = new LoginHandler();
    }

    /**
     * An atomic operation in Inchat.
     * An operation has a function run(), which returns its
     * result through a consumer.
     */
    @FunctionalInterface
    private interface Operation<T, E extends Throwable> {
        void run(final Consumer<T> result) throws E, DeletedException;
    }

    /**
     * Execute an operation atomically in SQL.
     * Wrapper method for commit() and rollback().
     */
    private <T> Maybe<T> atomic(Operation<T, SQLException> op) {
        synchronized (connection) {
            try {
                Maybe.Builder<T> result = Maybe.builder();
                op.run(result);
                connection.commit();
                return result.getMaybe();
            } catch (SQLException e) {
                System.err.println(e.toString());
            } catch (DeletedException e) {
                System.err.println(e.toString());
            }
            try {
                connection.rollback();
            } catch (SQLException e) {
                System.err.println(e.toString());
            }
            return Maybe.nothing();
        }
    }

    /**
     * Log in a user to the chat.
     * @return
     */
    public boolean login(String username, String password) {
        try {
            final Stored<Account> account = accountStore.lookup(username);
            if (!loginHandler.login(account.value, password)) {
                return Maybe.nothing();
            }
            final Stored<Session> session =
                    sessionStore.save(new Session(account, Instant.now().plusSeconds(60 * 60 * 24)));
            return Maybe.just(session);

        } catch (SQLException e) {
        } catch (DeletedException e) {
        }
        return Maybe.nothing();
    }

        /**
         * Register a new user.
         */
    public Maybe<Stored<Session>> register(String username, String password) {
          // Check to see if username is already used
          if(duplicate(username)) {
              System.err.println("Username " + username + " is already used");
              return Maybe.nothing();
          }
          try {
              final Stored<User> user =
                  userStore.save(User.create(username));
              final Stored<Account> account =
                  accountStore.save(Account.create(user, password));
              final Stored<Session> session =
                  sessionStore.save(new Session(account, Instant.now().plusSeconds(60*60*24)));
              return Maybe.just(session);
          } catch (SQLException e) {
         return Maybe.nothing();
         }
    }

    /**
     * Restore a previous session.
     */
    public Maybe<Stored<Session>> restoreSession(UUID sessionId) {
        try {
            return Maybe.just(sessionStore.get(sessionId));
        } catch (SQLException e) {
            System.err.println("When restoring session:" + e);
            return Maybe.nothing();
        } catch (DeletedException e) {
            return Maybe.nothing();
        }
    }

            /**
     * Log out and invalidate the session.
     */
    public void logout (Stored <Session> session) {
        try {
            Util.deleteSingle(session, sessionStore);
        } catch (SQLException e) {
            System.err.println("When loging out of session:" + e);
        }
    }

    /**
     * Create a new channel.
     */
    public Maybe<Stored<Channel>> createChannel (Stored <Account> Account, String name){
        try {
            if (!channelStore.channelExist(name)) {
                Stored<Channel> channel
                        = channelStore.save(new Channel(name, List.empty()));

                return joinChannel(Account, Role.Owner, channel.identity);

            }
        } catch (SQLException e) {
            System.err.println("When trying to create channel " + name + ":\n" + e);
        }
        return Maybe.nothing();
    }
    /**
     * Join a channel.
     */
    public Maybe<Stored<Channel>> joinChannel (Stored <Account> account, Role role,
            UUID channelID){
        try {
            Stored<Channel> channel = channelStore.get(channelID);
            Util.updateSingle(account,
                    accountStore,
                    a -> a.value.joinChannel(channel.value.name, channel, role));
            Stored<Channel.Event> joinEvent
                    = channelStore.eventStore.save(
                    Channel.Event.createJoinEvent(Instant.now(),
                            account.value.user.value.name.toString()));
            return Maybe.just(
                    Util.updateSingle(channel,
                            channelStore,
                            c -> c.value.postEvent(joinEvent)));
        } catch (DeletedException e) {
            // This channel has been deleted.
        } catch (SQLException e) {
            System.err.println("When trying to join " + channelID + ":\n" + e);
        }
        return Maybe.nothing();
    }

    public Stored<Channel> setRole (Stored <Account> Account, String username, String roleString, Stored <Channel> channel) {
        String alias = channel.value.name;
        Role role = Role.valueOf(roleString);
        try {
            if (account.value.getRole(alias).get() == Role.Owner) {
                Stored<Account> new_account = accountStore.lookup(username);
                if (!(new_account.equals(Account) && accountStore.numOfOwners(channel.identity) == 1)) {
                    Util.updateSingle(new_account, accountStore,
                            a -> a.value.setRole(alias, role));
                    return channel;
                }
            }
        } catch (NothingException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (SQLException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (DeletedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return channel;
    }
    /**
     * Post a message to a channel.
     */
    public Maybe<Stored<Channel>> postMessage (Stored <Account> account, Stored <Channel> channel,
            String message){
        return atomic(result -> {
            Stored<Channel.Event> event
                    = channelStore.eventStore.save(
                    Channel.Event.createMessageEvent(channel.identity, Instant.now(),
                            account.value.user.value.name, message));
            result.accept(
                    Util.updateSingle(channel,
                            channelStore,
                            c -> c.value.postEvent(event)));
        });
    }

    /**
     * A blocking call which returns the next state of the channel.
     */
    public Maybe<Stored<Channel>> waitNextChannelVersion (UUID identity, UUID version){
        try {
            return Maybe.just(channelStore.waitNextVersion(identity, version));
        } catch (DeletedException e) {
            return Maybe.nothing();
        } catch (SQLException e) {
            return Maybe.nothing();
        }
    }

    /**
     * Get an event by its identity.
     */
    public Maybe<Stored<Channel.Event>> getEvent (UUID eventID){
            return atomic(result ->
                    result.accept(channelStore.eventStore.get(eventID))
            );
        }

        /**
         * Delete an event.
         */
        public Stored<Channel> deleteEvent (Stored <Channel> channel, Stored <Channel.Event> event){
            return this.<Stored<Channel>>atomic(result -> {
                Util.deleteSingle(event, channelStore.eventStore);
                result.accept(channelStore.noChangeUpdate(channel.identity));
            }).defaultValue(channel);
        }

        /**
         * Edit a message.
         */
    public Stored<Channel> editMessage(Stored<Account> account, Stored<Channel> channel,
            Stored<Channel.Event> event,
            String newMessage) {
        try{
            String username = account.value.getName();
            Role role = account.value.getRole(channel.value.name).get();
            if(role.ordinal() >= 3 || (username.equals(event.value.sender) && role.ordinal() > 1)) {
                Util.updateSingle(event,
                        channelStore.eventStore,
                        e -> e.value.setMessage(newMessage));
                return channelStore.noChangeUpdate(channel.identity);
            }
        } catch (SQLException er) {
            System.err.println("While deleting event " + event.identity +":\n" + er);
        } catch (DeletedException er) {
            System.err.println("DeletedException");
        } catch (NothingException e1) {
            // TODO Auto-generated catch block
            System.err.println("NothingExcpetion");
            e1.printStackTrace();
        }
        System.err.println("Success");
        return channel;
    }

    public boolean duplicate(String username) {
        try {
            final Stored<Account> account = accountStore.lookup(username);
            if(account != null) {
                return true;
            }
        } catch (SQLException e) {
        } catch (DeletedException e) {
        }

        return false;
    }
    }




