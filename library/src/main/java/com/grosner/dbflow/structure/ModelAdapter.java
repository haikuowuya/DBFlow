package com.grosner.dbflow.structure;

import android.database.Cursor;
import com.grosner.dbflow.sql.builder.ConditionQueryBuilder;

/**
 * Author: andrewgrosner
 * Contributors: { }
 * Description:
 */
public abstract class ModelAdapter<ModelClass extends Model> implements InternalAdapter<ModelClass> {

    private ConditionQueryBuilder<ModelClass> mPrimaryWhere;

    public abstract ModelClass loadFromCursor(Cursor cursor);

    public abstract void save(boolean async, ModelClass model, int saveMode);

    /**
     * If a {@link com.grosner.dbflow.structure.Model} has an autoincrementing primary key, then
     * this method will be overridden.
     * @param model The model object to store the key
     * @param id The key to store
     */
    public void updateAutoIncrement(ModelClass model, long id) {

    }

    public abstract boolean exists(ModelClass model);

    public abstract void delete(boolean async, ModelClass model);

    public abstract ConditionQueryBuilder<ModelClass> getPrimaryModelWhere(ModelClass model);

    protected abstract ConditionQueryBuilder<ModelClass> createPrimaryModelWhere();

    public ConditionQueryBuilder<ModelClass> getPrimaryModelWhere() {
        if(mPrimaryWhere == null) {
            mPrimaryWhere = createPrimaryModelWhere();
        }
        mPrimaryWhere.setUseEmptyParams(true);
        return mPrimaryWhere;
    }

    public abstract String getCreationQuery();

    @Override
    public abstract Class<ModelClass> getModelClass();

    @Override
    public abstract String getTableName();

    public abstract ModelClass newInstance();
}
