/* This file is part of VoltDB.
 * Copyright (C) 2019 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

#pragma once

#include "persistenttable.h"

namespace voltdb {

// These IDs must be shared between the EE and the jvm layer
enum class SystemTableId : int32_t {
    KIPLING_GROUP = -1,
    KIPLING_GROUP_MEMBER = -2,
    KIPLING_GROUP_MEMBER_PROTOCOL = -3,
    KIPLING_GROUP_OFFSET = -4
};

class SystemTableFactory {

public:
    SystemTableFactory(int32_t compactionThreshold = 95): m_compactionThreshold(compactionThreshold) {}

    /**
     * Return a vector containing all SystemTableIds
     */
    static inline const std::vector<SystemTableId> getAllSystemTableIds() {
        return { SystemTableId::KIPLING_GROUP, SystemTableId::KIPLING_GROUP_MEMBER,
            SystemTableId::KIPLING_GROUP_MEMBER_PROTOCOL, SystemTableId::KIPLING_GROUP_OFFSET };
    }

    /**
     * Create a new PersistentTable for the system table with id.
     */
    PersistentTable* create(const SystemTableId id);

private:
    // Common utility method for creating a system table
    PersistentTable *createTable(char const *name, TupleSchema *schema, const std::vector<std::string> &columnNames,
            const int partitionColumn, const std::vector<int32_t> &primaryKeyColumns);

    // Methods for creating the different types of system tables
    PersistentTable *createKiplingGroup();
    PersistentTable *createKiplingGroupMember();
    PersistentTable *createKiplingGroupMemberProtocol();
    PersistentTable *createKiplingGroupOffset();

    // Member variables
    int32_t m_compactionThreshold;
};
}
