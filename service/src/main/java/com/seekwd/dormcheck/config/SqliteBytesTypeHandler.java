package com.seekwd.dormcheck.config;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes(byte[].class)
@MappedJdbcTypes({JdbcType.BLOB, JdbcType.VARBINARY})
public final class SqliteBytesTypeHandler extends BaseTypeHandler<byte[]> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, byte[] value, JdbcType jdbcType) throws SQLException {
        statement.setBytes(index, value);
    }

    @Override
    public byte[] getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return resultSet.getBytes(columnName);
    }

    @Override
    public byte[] getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return resultSet.getBytes(columnIndex);
    }

    @Override
    public byte[] getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return statement.getBytes(columnIndex);
    }
}
