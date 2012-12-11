/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.picketlink.idm.ldap.internal;

import static org.picketlink.idm.ldap.internal.LDAPConstants.CN;
import static org.picketlink.idm.ldap.internal.LDAPConstants.COMMA;
import static org.picketlink.idm.ldap.internal.LDAPConstants.EQUAL;
import static org.picketlink.idm.ldap.internal.LDAPConstants.MEMBER;
import static org.picketlink.idm.ldap.internal.LDAPConstants.SPACE_STRING;
import static org.picketlink.idm.ldap.internal.LDAPConstants.UID;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchResult;

import org.picketlink.idm.credential.Credentials;
import org.picketlink.idm.credential.PlainTextPassword;
import org.picketlink.idm.credential.X509CertificateCredentials;
import org.picketlink.idm.internal.util.Base64;
import org.picketlink.idm.model.Agent;
import org.picketlink.idm.model.Group;
import org.picketlink.idm.model.GroupRole;
import org.picketlink.idm.model.IdentityType;
import org.picketlink.idm.model.IdentityType.AttributeParameter;
import org.picketlink.idm.model.Role;
import org.picketlink.idm.model.SimpleGroupRole;
import org.picketlink.idm.model.User;
import org.picketlink.idm.query.QueryParameter;
import org.picketlink.idm.spi.IdentityStore;
import org.picketlink.idm.spi.IdentityStoreInvocationContext;

/**
 * An IdentityStore implementation backed by an LDAP directory
 * 
 * @author Shane Bryzak
 * @author Anil Saldhana
 * @author <a href="mailto:psilva@redhat.com">Pedro Silva</a>
 */
public class LDAPIdentityStore implements IdentityStore<LDAPConfiguration> {

    private static final String USER_CERTIFICATE_ATTRIBUTE = "usercertificate";
    private static final String USER_PASSWORD_ATTRIBUTE = "userpassword";

    private LDAPConfiguration configuration;
    private IdentityStoreInvocationContext context;

    @Override
    public void setup(LDAPConfiguration config, IdentityStoreInvocationContext context) {
        this.configuration = config;
        this.context = context;
    }

    @Override
    public LDAPConfiguration getConfig() {
        return this.configuration;
    }

    @Override
    public IdentityStoreInvocationContext getContext() {
        return this.context;
    }

    @Override
    public void add(IdentityType identityType) {
        if (User.class.isInstance(identityType)) {
            User user = (User) identityType;

            if (user.getId() == null) {
                throw new RuntimeException("No identifier was provided. You should provide one before storing the user.");
            }

            LDAPUser ldapUser = null;

            if (!(user instanceof LDAPUser)) {
                ldapUser = convert(user);
            } else {
                ldapUser = (LDAPUser) user;
            }

            ldapUser.setFullName(getUserCN(ldapUser));

            store(ldapUser);
        } else if (Group.class.isInstance(identityType)) {
            Group group = (Group) identityType;

            LDAPGroup ldapGroup = new LDAPGroup(this.configuration.getGroupDNSuffix());

            ldapGroup.setName(group.getName());

            store(ldapGroup);

            if (group.getParentGroup() != null) {
                String parentName = group.getParentGroup().getName();
                LDAPGroup parentGroup = (LDAPGroup) getGroup(parentName);

                if (parentGroup == null) {
                    throw new RuntimeException("Parent group [" + parentName + "] does not exists.");
                }

                parentGroup.addChildGroup(ldapGroup);

                ldapGroup.setParentGroup(parentGroup);

                getLdapManager().modifyAttribute(parentGroup.getDN(), parentGroup.getLDAPAttributes().get(MEMBER));
            }

        } else if (Role.class.isInstance(identityType)) {
            Role role = (Role) identityType;

            LDAPRole ldapRole = new LDAPRole(this.configuration.getRoleDNSuffix());

            ldapRole.setName(role.getName());

            store(ldapRole);
        }
    }

    @Override
    public void update(IdentityType identityType) {
        if (User.class.isInstance(identityType)) {
            User user = (User) identityType;

            LDAPUser updatedUser = convert(user);

            try {
                LDAPUser storedUser = (LDAPUser) getUser(user.getId());

                if (storedUser == null) {
                    throw new RuntimeException("User [" + user.getId() + "] does not exists.");
                }

                updatedUser.setFullName(getUserCN(updatedUser));

                NamingEnumeration<? extends Attribute> storedAttributes = storedUser.getLDAPAttributes().getAll();

                // check for attributes to replace or remove
                while (storedAttributes.hasMore()) {
                    Attribute storedAttribute = storedAttributes.next();
                    Attribute updatedAttribute = updatedUser.getLDAPAttributes().get(storedAttribute.getID());

                    // if the stored attribute exists in the updated attributes list, replace it. Otherwise remove it from the
                    // store.
                    if (updatedAttribute != null) {
                        getLdapManager().modifyAttribute(storedUser.getDN(), updatedAttribute);
                    } else {
                        getLdapManager().removeAttribute(storedUser.getDN(), storedAttribute);
                    }
                }

                NamingEnumeration<? extends Attribute> enumUpdatedAttributes = updatedUser.getLDAPAttributes().getAll();

                // check for attributes to add
                while (enumUpdatedAttributes.hasMore()) {
                    Attribute updatedAttribute = enumUpdatedAttributes.next();
                    Attribute storedAttribute = storedUser.getLDAPAttributes().get(updatedAttribute.getID());

                    // if the attribute is not stored and is a managed attribute add it to the store.
                    if (storedAttribute == null && getLdapManager().isManagedAttribute(updatedAttribute.getID())) {
                        getLdapManager().addAttribute(storedUser.getDN(), updatedAttribute);
                    }
                }

                updateCustomAttributes(updatedUser);
            } catch (NamingException e) {
                throw new RuntimeException(e);
            }
        } else if (Role.class.isInstance(identityType)) {
            Role role = (Role) identityType;
            LDAPRole storedRole = (LDAPRole) getRole(role.getName());

            if (storedRole == null) {
                throw new RuntimeException("No role found with the given name [" + role.getName() + "].");
            }

            LDAPRole updatedRole = (LDAPRole) role;

            updateCustomAttributes(updatedRole);
        } else if (Group.class.isInstance(identityType)) {
            Group group = (Group) identityType;

            LDAPGroup storedGroup = (LDAPGroup) getGroup(group.getName());

            if (storedGroup == null) {
                throw new RuntimeException("No group found with the given name [" + group.getName() + "].");
            }

            LDAPGroup updatedGroup = (LDAPGroup) group;

            updateCustomAttributes(updatedGroup);
        }
    }

    @Override
    public void remove(IdentityType identityType) {
        if (User.class.isInstance(identityType)) {
            User user = (User) identityType;

            LDAPUser ldapUser = (LDAPUser) getUser(user.getId());

            if (ldapUser == null) {
                throw new RuntimeException("User [" + user.getId() + "] does not exists.");
            }

            removeFromParent(this.configuration.getRoleDNSuffix(), ldapUser);
            removeFromParent(this.configuration.getGroupDNSuffix(), ldapUser);
            remove(ldapUser);
        } else if (Group.class.isInstance(identityType)) {
            Group group = (Group) identityType;

            LDAPGroup ldapGroup = (LDAPGroup) getGroup(group.getName());

            if (ldapGroup == null) {
                throw new RuntimeException("Group [" + group.getName() + "] doest not exists.");
            }

            remove(ldapGroup);

            // removes the custom grouprole entry from inside the user entries
            NamingEnumeration<SearchResult> results = getLdapManager().search(this.configuration.getUserDNSuffix(),
                    "(&(cn= " + ldapGroup.getName() + "*))");

            while (results.hasMoreElements()) {
                SearchResult searchResult = (SearchResult) results.nextElement();
                String dn = searchResult.getNameInNamespace();
                getLdapManager().destroySubcontext(dn);
            }

        } else if (Role.class.isInstance(identityType)) {
            Role role = (Role) identityType;

            LDAPRole ldapRole = (LDAPRole) getRole(role.getName());

            if (ldapRole == null) {
                throw new RuntimeException("Role [" + role.getName() + "] doest not exists.");
            }

            removeFromParent(this.configuration.getGroupDNSuffix(), ldapRole);
            remove(ldapRole);
        }
    }

    @Override
    public Agent getAgent(String id) {
        return getUser(id);
    }

    @Override
    public User getUser(String id) {
        final String baseDN = this.configuration.getUserDNSuffix();

        List<User> answer = getLdapManager().searchByAttribute(baseDN, UID, id, new LDAPSearchCallback<User>() {

            @Override
            public User processResult(SearchResult sr) {
                LDAPUser user = new LDAPUser(baseDN, sr.getAttributes());

                user.setCustomAttributes(getCustomAttributes(user.getDN()));

                return user;
            }

        });

        return answer.isEmpty() ? null : answer.get(0);
    }

    @Override
    public Group getGroup(String name) {
        final String baseDN = this.configuration.getGroupDNSuffix();

        List<Group> answer = getLdapManager().searchByAttribute(baseDN, CN, name, new LDAPSearchCallback<Group>() {

            @Override
            public Group processResult(SearchResult sr) {
                LDAPGroup ldapGroup = new LDAPGroup(sr.getAttributes(), baseDN);

                ldapGroup.setCustomAttributes(getCustomAttributes(ldapGroup.getDN()));

                Group parentGroup = getParentGroup(ldapGroup);

                if (parentGroup != null) {
                    ldapGroup.setParentGroup(parentGroup);
                }

                return ldapGroup;
            }

        });

        return answer.isEmpty() ? null : answer.get(0);
    }

    @Override
    public Role getRole(String name) {
        final String baseDN = this.configuration.getRoleDNSuffix();

        List<Role> answer = getLdapManager().searchByAttribute(baseDN, CN, name, new LDAPSearchCallback<Role>() {

            @Override
            public Role processResult(SearchResult sr) {
                LDAPRole ldapRole = new LDAPRole(sr.getAttributes(), baseDN);

                ldapRole.setCustomAttributes(getCustomAttributes(ldapRole.getDN()));

                return ldapRole;
            }

        });

        return answer.isEmpty() ? null : answer.get(0);
    }

    @Override
    public Group getGroup(String name, Group parent) {
        Group ldapGroup = getGroup(name);
        Group ldapGroupParent = ldapGroup.getParentGroup();

        if (parent != null && ldapGroup != null && ldapGroupParent != null
                && ldapGroupParent.getName().equals(parent.getName())) {
            return ldapGroup;
        }

        return null;
    }

    @Override
    public GroupRole createMembership(IdentityType member, Group group, Role role) {
        if (member instanceof User) {
            User user = getUser(((User) member).getId());

            LDAPRole ldapRole = null;

            if (role != null) {
                ldapRole = (LDAPRole) getRole(role.getName());
            }

            LDAPUser ldapUser = null;

            if (user != null) {
                ldapUser = (LDAPUser) getUser(user.getId());
            }

            LDAPGroup ldapGroup = null;

            if (group != null) {
                ldapGroup = (LDAPGroup) getGroup(group.getName());
            }

            if (ldapRole != null && ldapGroup != null) {
                LDAPGroupRole groupRole = new LDAPGroupRole(ldapUser, ldapGroup, ldapRole);
                storeMembershipEntry(groupRole, ldapRole);
            } else {
                if (ldapUser != null && ldapRole != null) {
                    addMember(ldapRole, ldapUser);
                }

                if (ldapGroup != null && ldapRole != null) {
                    addMember(ldapGroup, ldapRole);
                }

                if (ldapGroup != null && ldapUser != null) {
                    addMember(ldapGroup, ldapUser);
                }
            }

            return new SimpleGroupRole(ldapUser, ldapRole, ldapGroup);
        } else if (member instanceof Group) {
            // FIXME implement Group membership, or return null
            return null;
        } else {
            throw new IllegalArgumentException("The member parameter must be an instance of User or Group");
        }
    }

    @Override
    public void removeMembership(IdentityType member, Group group, Role role) {
        if (member instanceof User) {
            LDAPUser ldapUser = (LDAPUser) getUser(((User) member).getId());

            LDAPRole ldapRole = null;

            if (role != null) {
                ldapRole = (LDAPRole) getRole(role.getName());
            }

            LDAPGroup ldapGroup = null;

            if (group != null) {
                ldapGroup = (LDAPGroup) getGroup(group.getName());
            }

            if (group != null && role != null) {
                LDAPGroupRole groupRole = new LDAPGroupRole(ldapUser, ldapGroup, ldapRole);
                removeMemberShipEntry(groupRole, ldapRole);
            } else {
                if (ldapRole != null) {
                    removeMember(ldapRole, ldapUser);
                }

                if (ldapGroup != null) {
                    removeMember(ldapGroup, ldapUser);
                }
            }
        } else if (member instanceof Group) {
            // FIXME implement Group membership if supported
        }
    }

    @Override
    public GroupRole getMembership(IdentityType member, Group group, Role role) {
        GroupRole groupRole = null;

        LDAPUser ldapUser = (LDAPUser) getUser(((User) member).getId());

        if (group != null && role != null) {
            LDAPRole ldapRole = (LDAPRole) getRole(((Role) role).getName());
            LDAPGroup ldapGroup = (LDAPGroup) getGroup(((Group) group).getName());

            String dn = new LDAPGroupRole(ldapUser, ldapGroup, ldapRole).getDN();

            groupRole = getLdapManager().lookup(dn);

            LDAPGroupRole ldapGroupRole = (LDAPGroupRole) groupRole;

            if (groupRole == null || !ldapGroupRole.isMember(ldapRole)) {
                groupRole = null;
            }
        } else {
            if (role != null) {
                LDAPRole ldapRole = (LDAPRole) getRole(role.getName());

                if (ldapRole.isMember(ldapUser)) {
                    groupRole = new SimpleGroupRole(ldapUser, getRole(role.getName()), null);
                }
            }

            if (group != null) {
                LDAPGroup ldapGroup = (LDAPGroup) getGroup(group.getName());

                if (ldapGroup.isMember(ldapUser)) {
                    groupRole = new SimpleGroupRole(ldapUser, null, group);
                }
            }
        }

        return groupRole;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.picketlink.idm.spi.IdentityStore#setAttribute(org.picketlink.idm.model.User, java.lang.String,
     * java.lang.String[])
     */
    @Override
    public void setAttribute(IdentityType identity, org.picketlink.idm.model.Attribute<? extends Serializable> attribute) {
        throw new RuntimeException("Not implemented yet.");
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.picketlink.idm.spi.IdentityStore#removeAttribute(org.picketlink.idm.model.User, java.lang.String)
     */
    @Override
    public void removeAttribute(IdentityType identity, String name) {
        throw new RuntimeException("Not implemented yet.");
    }

    // @Override
    public boolean validateCredential(User user, Object credential) {
        LDAPUser ldapUser = (LDAPUser) getUser(user.getId());

        if (ldapUser == null) {
            return false;
        }

        boolean valid = false;

        if (credential instanceof PlainTextPassword) {
            PlainTextPassword password = (PlainTextPassword) credential;

            valid = getLdapManager().authenticate(ldapUser.getDN(), new String(password.getValue()));
        } else if (credential instanceof X509CertificateCredentials) {
            X509CertificateCredentials clientCert = (X509CertificateCredentials) credential;

            org.picketlink.idm.model.Attribute<Serializable> certAttribute = ldapUser.getAttribute(USER_CERTIFICATE_ATTRIBUTE);

            byte[] certBytes = Base64.decode(new String((byte[]) certAttribute.getValue()));

            try {
                CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
                X509Certificate storedCert = (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(
                        certBytes));
                X509Certificate providedCert = clientCert.getCertificate();

                valid = storedCert.equals(providedCert);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return valid;
    }

    // @Override
    public void updateCredential(User user, Object credential) {
        if (credential instanceof PlainTextPassword) {
            PlainTextPassword password = (PlainTextPassword) credential;
            if (this.configuration.isActiveDirectory()) {
                updateADPassword((LDAPUser) user, new String(password.getValue()));
            } else {
                LDAPUser ldapuser = null;
                if (user instanceof LDAPUser == false) {
                    ldapuser = convert(user);
                } else {
                    ldapuser = (LDAPUser) user;
                }

                ModificationItem[] mods = new ModificationItem[1];

                Attribute mod0 = new BasicAttribute(USER_PASSWORD_ATTRIBUTE, password.getValue());

                mods[0] = new ModificationItem(DirContext.REPLACE_ATTRIBUTE, mod0);

                getLdapManager().modifyAttribute(ldapuser.getDN(), mod0);
            }
        } else if (credential instanceof X509CertificateCredentials) {
            X509CertificateCredentials cc = (X509CertificateCredentials) credential;
            try {
                LDAPUser ldapUser = (LDAPUser) user;

                String certbytes = Base64.encodeBytes(cc.getCertificate().getEncoded());

                ldapUser.setAttribute(new org.picketlink.idm.model.Attribute<String>(USER_CERTIFICATE_ATTRIBUTE, certbytes));

                BasicAttribute certAttribute = new BasicAttribute(USER_CERTIFICATE_ATTRIBUTE, certbytes);

                // Perform the update
                getLdapManager().modifyAttribute(ldapUser.getDN(), certAttribute);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            throwsNotSupportedCredentialType(credential);
        }
    }

    @Override
    public <T extends IdentityType> List<T> fetchQueryResults(Class<T> typeClass, Map<QueryParameter, Object[]> parameters) {
        List<T> result = new ArrayList<T>();
        LDAPQuery ldapQuery = new LDAPQuery(parameters);

        if (User.class.isAssignableFrom(typeClass)) {
            StringBuffer filter = ldapQuery.createManagedAttributesFilter();

            if (filter == null) {
                filter = new StringBuffer("(&(objectClass=*)(uid=*))");
            }

            NamingEnumeration<SearchResult> answer = null;
            
            StringBuffer filterUsers = new StringBuffer();
            
            for (LDAPQueryParameter ldapQueryParameter : ldapQuery.getMemberShipParameters()) {
                QueryParameter queryParameter = ldapQueryParameter.getQueryParameter();
                
                if (queryParameter.equals(User.HAS_ROLE) || queryParameter.equals(User.MEMBER_OF)) {
                    if (queryParameter.equals(User.HAS_ROLE)) {
                        for (Object role : ldapQueryParameter.getValues()) {
                            LDAPRole ldapRole = (LDAPRole) getRole(role.toString());
                            
                            Attribute members = ldapRole.getLDAPAttributes().get(MEMBER);
                            
                            try {
                                NamingEnumeration<?> all = members.getAll();
                                
                                while (all.hasMoreElements()) {
                                    Object object = (Object) all.nextElement();
                                    
                                    if (object.toString().trim().isEmpty()) {
                                        continue;
                                    }
                                    
                                    filterUsers.append("(").append(object.toString().substring(0, object.toString().indexOf(','))).append(")");
                                }
                            } catch (NamingException e) {
                                e.printStackTrace();
                            }
                        }
                    } 
                    
                    if (queryParameter.equals(User.MEMBER_OF)) {
                        for (Object group : ldapQueryParameter.getValues()) {
                            LDAPGroup ldapGroup = (LDAPGroup) getGroup(group.toString());
                            
                            Attribute members = ldapGroup.getLDAPAttributes().get(MEMBER);
                            
                            try {
                                NamingEnumeration<?> all = members.getAll();
                                
                                while (all.hasMoreElements()) {
                                    Object object = (Object) all.nextElement();
                                    
                                    if (object.toString().trim().isEmpty()) {
                                        continue;
                                    }
                                    
                                    filterUsers.append("(").append(object.toString().substring(0, object.toString().indexOf(','))).append(")");
                                }
                            } catch (NamingException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                } else {
                    String hasMemberFilter = "(|";

                    for (Object group : ldapQueryParameter.getValues()) {
                        GroupRole groupRole = (GroupRole) group;
                        hasMemberFilter = hasMemberFilter + "(cn=" + groupRole.getGroup().getName() + ")";
                    }

                    hasMemberFilter = hasMemberFilter + ")";

                    NamingEnumeration<SearchResult> search = null;

                    try {
                        search = getLdapManager().search(this.configuration.getUserDNSuffix(),
                                hasMemberFilter.toString());
                        
                        if (search.hasMoreElements()) {
                            for (Object group : ldapQueryParameter.getValues()) {
                                GroupRole groupRole = (GroupRole) group;

                                while (search.hasMoreElements()) {
                                    SearchResult searchResult = search.next();
                                    Attributes attributes2 = searchResult.getAttributes();
                                    Attribute member = attributes2.get(MEMBER);

                                    if (!member.contains("cn=" + groupRole.getRole().getName() + COMMA
                                            + this.configuration.getRoleDNSuffix())) {
                                        break;
                                    } else {
                                        String[] nameInNamespace = searchResult.getNameInNamespace().split(",");
                                        filterUsers.append("(").append(nameInNamespace[1]).append(")");
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        if (search != null) {
                            try {
                                search.close();
                            } catch (NamingException e) {
                            }
                        }
                    }
                }
            }
            
            if (filterUsers.length() == 0 && !ldapQuery.getMemberShipParameters().isEmpty()) {
                return result;
            }
            
            try {
                if (filterUsers.length() > 0) {
                    filterUsers.insert(0, "(|");
                    filterUsers.insert(filterUsers.length() - 1, ")");
                }
                
                filter.insert(filter.length() - 1, filterUsers.toString());
                answer = getLdapManager().search(this.configuration.getUserDNSuffix(), filter.toString(), new String[] { UID },
                        null);

                while (answer.hasMoreElements()) {
                    SearchResult sr = (SearchResult) answer.nextElement();
                    Attributes attributes = sr.getAttributes();
                    String uid = null;

                    uid = (String) attributes.get(UID).get();
                    
                    // let's check if there are some memberships parameters (HAS_ROLE, MEMBER_OF, etc) and filter the current user entry.
                    for (LDAPQueryParameter ldapQueryParameter : ldapQuery.getMemberShipParameters()) {
                        QueryParameter queryParameter = ldapQueryParameter.getQueryParameter();

                        String hasMemberFilter = "(&(member=uid=" + uid + "*)(|";

                        if (queryParameter.equals(User.HAS_ROLE) || queryParameter.equals(User.MEMBER_OF)) {
                            String dnSuffix = null;

                            if (queryParameter.equals(User.HAS_ROLE)) {
                                dnSuffix = this.configuration.getRoleDNSuffix();
                                for (Object role : ldapQueryParameter.getValues()) {
                                    hasMemberFilter = hasMemberFilter + "(cn=" + role + ")";
                                }
                            } else {
                                dnSuffix = this.configuration.getGroupDNSuffix();
                                for (Object group : ldapQueryParameter.getValues()) {
                                    hasMemberFilter = hasMemberFilter + "(cn=" + group + ")";
                                }
                            }

                            hasMemberFilter = hasMemberFilter + "))";

                            NamingEnumeration<SearchResult> search = null;

                            try {
                                search = getLdapManager().search(dnSuffix, hasMemberFilter);
                                int count = 0;

                                while (search.hasMoreElements()) {
                                    search.nextElement();
                                    count++;
                                }

                                if (count == 0 || count != ldapQueryParameter.getValues().length) {
                                    uid = null;
                                    break;
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                if (search != null) {
                                    search.close();
                                }
                            }
                        } else if (queryParameter.equals(User.HAS_GROUP_ROLE)) {
                            hasMemberFilter = "(|";

                            for (Object group : ldapQueryParameter.getValues()) {
                                GroupRole groupRole = (GroupRole) group;
                                hasMemberFilter = hasMemberFilter + "(cn=" + groupRole.getGroup().getName() + ")";
                            }

                            hasMemberFilter = hasMemberFilter + ")";

                            NamingEnumeration<SearchResult> search = null;

                            try {
                                search = getLdapManager().search("uid=" + uid + COMMA + this.configuration.getUserDNSuffix(),
                                        hasMemberFilter.toString());
                                
                                if (!search.hasMoreElements()) {
                                    uid = null;
                                    break;
                                }

                                for (Object group : ldapQueryParameter.getValues()) {
                                    GroupRole groupRole = (GroupRole) group;

                                    while (search.hasMoreElements()) {
                                        Attributes attributes2 = search.next().getAttributes();
                                        Attribute member = attributes2.get(MEMBER);

                                        if (!member.contains("cn=" + groupRole.getRole().getName() + COMMA
                                                + this.configuration.getRoleDNSuffix())) {
                                            uid = null;
                                            break;
                                        }
                                    }
                                }

                                if (uid == null) {
                                    break;
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                if (search != null) {
                                    search.close();
                                }
                            }
                        }
                    }

                    if (uid == null) {
                        continue;
                    }

                    LDAPUser transientUser = new LDAPUser(this.configuration.getUserDNSuffix());

                    transientUser.setId(uid);

                    if (ldapQuery.hasCustomAttributes()) {
                        LDAPCustomAttributes customAttributes = getCustomAttributes(transientUser.getDN());
                        
                        if (customAttributes == null) {
                            uid = null;
                            continue;
                        }
                        
                        for (LDAPQueryParameter ldapQueryParameter : ldapQuery.getCustomParameters()) {
                            QueryParameter queryParameter = ldapQueryParameter.getQueryParameter();
                            Object[] values = ldapQueryParameter.getValues();
                            String id = null;
                            Object entryValue = null;
                            boolean hasAttribute = false;

                            if (!ldapQueryParameter.isMappedToManagedAttribute()) {
                                if (ldapQueryParameter.getMappedTo() != null) {
                                    id = ldapQueryParameter.getMappedTo().getID();
                                } else {
                                    if (queryParameter instanceof IdentityType.AttributeParameter) {
                                        id = ((IdentityType.AttributeParameter) queryParameter).getName();
                                    } else {
                                        id = "";
                                    }
                                }

                                entryValue = customAttributes.getAttribute(id);

                                if (entryValue != null) {
                                    hasAttribute = true;
                                } else {
                                    uid = null;
                                    break;
                                }

                                if (id.equals(LDAPConstants.CUSTOM_ATTRIBUTE_CREATE_DATE)
                                        || id.equals(LDAPConstants.CUSTOM_ATTRIBUTE_EXPIRY_DATE)) {
                                    long providedTimeInMillis = ((Date) values[0]).getTime();
                                    long storedTimeInMillis = Long.valueOf(entryValue.toString());

                                    if (queryParameter.equals(User.CREATED_DATE) || queryParameter.equals(User.EXPIRY_DATE)) {
                                        if (providedTimeInMillis != storedTimeInMillis) {
                                            uid = null;
                                            break;
                                        }
                                    } else if (id.equals(LDAPConstants.CUSTOM_ATTRIBUTE_CREATE_DATE)) {
                                        if (queryParameter.equals(User.CREATED_AFTER)) {
                                            if (storedTimeInMillis < providedTimeInMillis) {
                                                uid = null;
                                                break;
                                            }
                                        }

                                        if (queryParameter.equals(User.CREATED_BEFORE)) {
                                            if (storedTimeInMillis > providedTimeInMillis) {
                                                uid = null;
                                                break;
                                            }
                                        }
                                    } else if (id.equals(LDAPConstants.CUSTOM_ATTRIBUTE_EXPIRY_DATE)) {
                                        if (queryParameter.equals(User.EXPIRY_AFTER)) {
                                            if (storedTimeInMillis < providedTimeInMillis) {
                                                uid = null;
                                                break;
                                            }
                                        }

                                        if (queryParameter.equals(User.EXPIRY_BEFORE)) {
                                            if (storedTimeInMillis > providedTimeInMillis) {
                                                uid = null;
                                                break;
                                            }
                                        }
                                    }
                                } else {
                                    if (queryParameter instanceof IdentityType.AttributeParameter) {
                                        IdentityType.AttributeParameter attrParameter = (AttributeParameter) queryParameter;

                                        if (id.equals(attrParameter.getName())) {
                                            hasAttribute = true;

                                            if (entryValue.getClass().isArray()) {
                                                Object[] attributeValues = (Object[]) entryValue;
                                                int matchCount = 0;

                                                for (Object object : attributeValues) {
                                                    for (Object parameterValue : values) {
                                                        if (object.toString().equals(parameterValue.toString())) {
                                                            matchCount++;
                                                        }
                                                    }
                                                }

                                                if (matchCount != attributeValues.length) {
                                                    uid = null;
                                                    break;
                                                }
                                            } else {
                                                if (!values[0].toString().equals(entryValue.toString())) {
                                                    uid = null;
                                                    break;
                                                }
                                            }
                                        }
                                    } else {
                                        if (!values[0].toString().equals(entryValue.toString())) {
                                            uid = null;
                                            break;
                                        }
                                    }
                                }

                                if (!hasAttribute) {
                                    uid = null;
                                }
                            }
                        }
                    }

                    if (uid != null) {
                        result.add((T) getUser(uid));
                    }
                }
            } catch (NamingException ne) {
                throw new RuntimeException(ne);
            } finally {
                if (answer != null) {
                    try {
                        answer.close();
                    } catch (NamingException e) {
                    }
                }
            }

        }

        return result;
    }

    @Override
    public <T extends Serializable> org.picketlink.idm.model.Attribute<T> getAttribute(IdentityType identityType,
            String attributeName) {
        // TODO Auto-generated method stub
        return null;
    }

    // Remember the updation has to happen over SSL. That is handled by the JNDI Ctx Parameters
    private void updateADPassword(LDAPUser user, String password) {
        try {
            // Replace the "unicdodePwd" attribute with a new value
            // Password must be both Unicode and a quoted string
            String newQuotedPassword = "\"" + password + "\"";
            byte[] newUnicodePassword = newQuotedPassword.getBytes("UTF-16LE");

            BasicAttribute unicodePwd = new BasicAttribute("unicodePwd", newUnicodePassword);

            getLdapManager().modifyAttribute(user.getDN(), unicodePwd);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * <p>
     * Helper method to throws a {@link IllegalArgumentException} when the specified {@link Credential} is not supported.
     * </p>
     * TODO: when using JBoss Logging this method should be removed.
     * 
     * @param credential
     * @return
     */
    private void throwsNotSupportedCredentialType(Object credential) throws IllegalArgumentException {
        throw new IllegalArgumentException("Credential type not supported: " + credential.getClass());
    }

    /**
     * <p>
     * Converts the given {@link User} instance to a {@link LDAPUser} instance.
     * </p>
     * 
     * @param user
     * @return
     */
    private LDAPUser convert(User user) {
        LDAPUser ldapUser = null;

        if (user instanceof LDAPUser) {
            ldapUser = (LDAPUser) user;
        } else {
            ldapUser = new LDAPUser(this.configuration.getUserDNSuffix());

            ldapUser.setId(user.getId());
            ldapUser.setFirstName(" ");
            ldapUser.setLastName(" ");

            if (user.getFirstName() != null) {
                ldapUser.setFirstName(user.getFirstName());
            }

            if (user.getLastName() != null) {
                ldapUser.setLastName(user.getLastName());
            }

            if (user.getEmail() != null) {
                ldapUser.setEmail(user.getEmail());
            }

            if (user.getExpirationDate() != null) {
                ldapUser.setExpirationDate(user.getExpirationDate());
            }

            for (org.picketlink.idm.model.Attribute<? extends Serializable> attrib : user.getAttributes()) {
                ldapUser.setAttribute(attrib);
            }
        }

        return ldapUser;
    }

    /**
     * <p>
     * Returns the custom attributes for the given parent DN.
     * </p>
     * 
     * @param parentDN
     * @return
     */
    private LDAPCustomAttributes getCustomAttributes(String parentDN) {
        String customDN = getCustomAttributesDN(parentDN);

        LDAPCustomAttributes customAttributes = null;

        try {
            customAttributes = getLdapManager().lookup(customDN);
        } catch (Exception ignore) {
        }

        return customAttributes;
    }

    /**
     * <p>
     * Returns a DN for the custom attributes entry.
     * </p>
     * 
     * @param parentDN
     * @return
     */
    private String getCustomAttributesDN(String parentDN) {
        return "cn=custom-attributes" + COMMA + parentDN;
    }

    /**
     * <p>
     * Returns the user CN attribute value. The CN is composed of user's first and last name.
     * </p>
     * 
     * @param ldapUser
     * @return
     */
    private String getUserCN(LDAPUser ldapUser) {
        String fullName = ldapUser.getFirstName();

        if (ldapUser.getLastName() != null) {
            fullName = fullName + " " + ldapUser.getLastName();
        }
        return fullName;
    }

    /**
     * <p>
     * Stores the given {@link LDAPEntry} instance in the LDAP tree. This method performs a bind for both {@link LDAPEntry}
     * instance and its {@link LDAPCustomAttributes}.
     * </p>
     * 
     * @param ldapEntry
     */
    private void store(LDAPEntry ldapEntry) {
        getLdapManager().bind(ldapEntry.getDN(), ldapEntry);
        getLdapManager().bind(getCustomAttributesDN(ldapEntry.getDN()), ldapEntry.getCustomAttributes());
    }

    private void addMember(LDAPEntry parentEntry, LDAPEntry childEntry) {
        parentEntry.addMember(childEntry);
        getLdapManager().modifyAttribute(parentEntry.getDN(), parentEntry.getLDAPAttributes().get(MEMBER));
    }

    private void removeMember(LDAPEntry parentEntry, LDAPEntry childEntry) {
        parentEntry.removeMember(childEntry);
        getLdapManager().modifyAttribute(parentEntry.getDN(), parentEntry.getLDAPAttributes().get(MEMBER));
    }

    /**
     * <p>
     * Removes the given {@link LDAPEntry} entry from the LDAP tree. This method also remove the custom attribute entry for the
     * given parent instance.
     * </p>
     * 
     * @param ldapEntry
     */
    private void remove(LDAPEntry ldapEntry) {
        getLdapManager().destroySubcontext(ldapEntry.getDN());
    }

    /**
     * <p>
     * Returns the parent group for the given child group.
     * </p>
     * 
     * @param childGroup
     * @return
     */
    private Group getParentGroup(LDAPGroup childGroup) {
        Attributes matchAttrs = new BasicAttributes(true);

        matchAttrs.put(new BasicAttribute(MEMBER, CN + EQUAL + childGroup.getName() + COMMA
                + this.configuration.getGroupDNSuffix()));

        NamingEnumeration<SearchResult> answer = null;

        // Search for objects with these matching attributes
        try {
            answer = getLdapManager().search(this.configuration.getGroupDNSuffix(), matchAttrs, new String[] { CN });
            while (answer.hasMoreElements()) {
                SearchResult sr = (SearchResult) answer.nextElement();
                Attributes attributes = sr.getAttributes();
                String cn = (String) attributes.get(CN).get();
                return getGroup(cn);
            }
        } catch (NamingException e) {
            throw new RuntimeException("Error looking parent group for [" + childGroup.getDN() + "]", e);
        } finally {
            if (answer != null) {
                try {
                    answer.close();
                } catch (NamingException e) {
                }
            }
        }

        return null;
    }

    /**
     * <p>
     * Updates the custom attributes for the given {@link LDAPEntry} instance.
     * </p>
     * 
     * @param ldapEntry
     */
    private void updateCustomAttributes(LDAPEntry ldapEntry) {
        LDAPCustomAttributes attributes = ldapEntry.getCustomAttributes();

        Set<Entry<String, Object>> entrySet = new HashMap<String, Object>(attributes.getAttributes()).entrySet();

        for (Entry<String, Object> entry : entrySet) {
            // if the custom attribute is managed, add it to the LDAP managed attributes list. Otherwise remove it from the
            // list of LDAP managed attributes.
            if (getLdapManager().isManagedAttribute(entry.getKey())) {
                ldapEntry.getLDAPAttributes().put(entry.getKey(), entry.getValue());
                attributes.removeAttribute(entry.getKey());
            } else {
                ldapEntry.getLDAPAttributes().remove(entry.getKey());
            }
        }

        getLdapManager().rebind(getCustomAttributesDN(ldapEntry.getDN()), attributes);
    }

    /**
     * <p>
     * Finds all parent entries where the specified {@link LDAPEntry} is configured as a member.
     * </p>
     * 
     * @param ldapUser
     * @return
     */
    private NamingEnumeration<SearchResult> findParentEntries(String dnSuffix, LDAPEntry member) {
        String filter = "(member=" + member.getDN() + ")";

        return getLdapManager().search(dnSuffix, filter);
    }

    /**
     * <p>
     * Remove from parent entries inside the given <code>dnSuffix</code> the specified {@link LDAPEntry}.
     * </p>
     * 
     * @param dnSuffix
     * @param member
     */
    private void removeFromParent(String dnSuffix, LDAPEntry member) {
        NamingEnumeration<SearchResult> results = null;

        try {
            results = findParentEntries(dnSuffix, member);

            while (results.hasMoreElements()) {
                SearchResult searchResult = (SearchResult) results.nextElement();
                Attribute memberAttribute = searchResult.getAttributes().get(MEMBER);

                if (memberAttribute != null) {
                    memberAttribute.remove(member.getDN());
                }

                if (!memberAttribute.getAll().hasMoreElements()) {
                    memberAttribute.add(SPACE_STRING);
                }
            }
        } catch (NamingException ne) {
            throw new RuntimeException(ne);
        } finally {
            if (results != null) {
                try {
                    results.close();
                } catch (NamingException e) {
                }
            }
        }

    }

    private void storeMembershipEntry(LDAPEntry ldapEntry, LDAPEntry member) {
        String dn = ldapEntry.getDN();

        LDAPEntry storedGroupRole = getLdapManager().lookup(dn);

        if (storedGroupRole == null) {
            storedGroupRole = ldapEntry;
            getLdapManager().bind(dn, storedGroupRole);
        } else {
            Attribute memberAttribute = storedGroupRole.getLDAPAttributes().get(MEMBER);

            if (!memberAttribute.contains(member.getDN())) {
                memberAttribute.add(member.getDN());
                getLdapManager().modifyAttribute(dn, memberAttribute);
                getLdapManager().rebind(dn, storedGroupRole);
            }
        }
    }

    private void removeMemberShipEntry(LDAPEntry ldapEntry, LDAPEntry member) {
        String dn = ldapEntry.getDN();

        LDAPEntry storedGroupRole = getLdapManager().lookup(dn);

        if (storedGroupRole != null) {
            Attribute memberAttribute = storedGroupRole.getLDAPAttributes().get(MEMBER);

            if (memberAttribute.contains(member.getDN())) {
                memberAttribute.remove(member.getDN());
                memberAttribute.add(SPACE_STRING);
                getLdapManager().modifyAttribute(dn, memberAttribute);
                getLdapManager().rebind(dn, storedGroupRole);
            }
        }
    }

    public LDAPOperationManager getLdapManager() {
        return this.configuration.getLdapManager();
    }

    @Override
    public void validateCredentials(Credentials credentials) {
        // TODO Auto-generated method stub

    }

    @Override
    public void updateCredential(Agent agent, Object credential) {
        // TODO Auto-generated method stub

    }
}