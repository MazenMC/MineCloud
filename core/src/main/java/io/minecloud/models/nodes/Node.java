/*
 * Copyright (c) 2015, Mazen Kotb <email@mazenmc.io>
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package io.minecloud.models.nodes;

import io.minecloud.MineCloud;
import io.minecloud.db.mongo.model.MongoEntity;
import io.minecloud.models.bungee.Bungee;
import io.minecloud.models.nodes.type.NodeType;
import io.minecloud.models.server.Server;
import lombok.Setter;
import org.mongodb.morphia.annotations.Entity;
import org.mongodb.morphia.annotations.Reference;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Entity(value = "nodes", noClassnameStored = true)
public class Node extends MongoEntity {
    @Setter
    private String publicIp;
    @Setter
    private String privateIp;
    @Reference(lazy = true)
    @Setter
    private NodeType type;
    @Setter
    private double availableRam;
    @Setter
    private List<CoreMetadata> coreMetadata;

    public String name() {
        return entityId();
    }

    public String privateIp() {
        return privateIp;
    }

    public String publicIp() {
        return publicIp;
    }

    public NodeType type() {
        return type;
    }

    public List<CoreMetadata> coreMetadata() {
        return coreMetadata;
    }

    double totalUsage() {
        double total = 0;

        for (int i = 0; i < coreMetadata.size(); i++) {
            total += usage(i);
        }

        return total;
    }

    private double usage(int core) {
        if (core >= coreMetadata.size()) {
            return -1;
        }

        return coreMetadata.get(core).usage();
    }

    double allocatedRam() {
        Collection<Server> servers = MineCloud.instance().mongo()
                .repositoryBy(Server.class)
                .findAll((server) -> server.node().name().equals(name()));
        Collection<Bungee> bungees = MineCloud.instance().mongo()
                .repositoryBy(Bungee.class)
                .findAll((bungee) -> bungee.node().name().equals(name()));
        int ramUsed = 0;

        for (Server server : servers) {
            ramUsed += server.type().dedicatedRam();
        }

        for (Bungee bungee : bungees) {
            ramUsed += bungee.type().dedicatedRam();
        }

        return type.ram() - ramUsed;
    }

    double availableRam() {
        return availableRam;
    }

    public List<Server> servers() {
        return MineCloud.instance().mongo().repositoryBy(Server.class).models()
                .stream()
                .filter((server) -> server.node().equals(this))
                .collect(Collectors.toList());
    }

    int serverCount() {
        return servers().size();
    }

    public void setName(String name) {
        setId(name);
    }
}
