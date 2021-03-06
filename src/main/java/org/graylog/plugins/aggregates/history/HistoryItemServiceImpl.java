package org.graylog.plugins.aggregates.history;

import com.google.common.collect.Lists;

import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.util.JSON;
import com.mongodb.BasicDBObject;
import com.thoughtworks.xstream.converters.extended.ISO8601DateConverter;

import org.graylog2.bindings.providers.MongoJackObjectMapperProvider;
import org.graylog2.database.CollectionName;
import org.graylog2.database.MongoConnection;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.mongojack.Aggregation;
import org.mongojack.Aggregation.Project;
import org.mongojack.AggregationResult;
import org.mongojack.DBCursor;
import org.mongojack.DBProjection;
import org.mongojack.DBProjection.ProjectionBuilder;
import org.mongojack.DBQuery;
import org.mongojack.DBSort;
import org.mongojack.JacksonDBCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;

public class HistoryItemServiceImpl implements HistoryItemService {

	private final JacksonDBCollection<HistoryItemImpl, String> coll;
	private final Validator validator;
	private static final Logger LOG = LoggerFactory.getLogger(HistoryItemServiceImpl.class);

	@Inject
	public HistoryItemServiceImpl(MongoConnection mongoConnection, MongoJackObjectMapperProvider mapperProvider,
			Validator validator) {
		LOG.info("constructor");
		this.validator = validator;
		final String collectionName = HistoryItemImpl.class.getAnnotation(CollectionName.class).value();
		final DBCollection dbCollection = mongoConnection.getDatabase().getCollection(collectionName);
		this.coll = JacksonDBCollection.wrap(dbCollection, HistoryItemImpl.class, String.class, mapperProvider.get());
		// this.coll.createIndex(new BasicDBObject("name", 1), new
		// BasicDBObject("unique", true));
	}

	@Override
	public long count() {
		return coll.count();
	}

	@Override
	public HistoryItem create(HistoryItem historyItem) {
		if (historyItem instanceof HistoryItemImpl) {
			final HistoryItemImpl ruleImpl = (HistoryItemImpl) historyItem;
			final Set<ConstraintViolation<HistoryItemImpl>> violations = validator.validate(ruleImpl);
			if (violations.isEmpty()) {
				return coll.insert(ruleImpl).getSavedObject();

			} else {
				throw new IllegalArgumentException("Specified object failed validation: " + violations);
			}
		} else
			throw new IllegalArgumentException(
					"Specified object is not of correct implementation type (" + historyItem.getClass() + ")!");
	}

	@Override
	public List<HistoryItem> all() {
		return toAbstractListType(coll.find());
	}
	
	
	@Override
	public List<HistoryAggregateItem> getForRuleName(String ruleName, int days) {
		Calendar c = Calendar.getInstance();
		c.add(Calendar.DATE, days * -1);
		
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd");

		//first match the records that meet the rule and the number of days 
		DBObject match = (DBObject) JSON.parse("{ '$match' : { $and: [ {'ruleName': '" + ruleName +"'}, {'timestamp': {'$gt' : { '$date': '" + df.format(c.getTime()) + "T00:00:00.000Z'}}}]}}");
		
		//then create a projection, so we can group by the date part
		DBObject project = (DBObject) JSON.parse(
				"{'$project': { 'datePartDay' : {'$concat' : [ {'$substr' : [{'$year' : '$timestamp'}, 0, 4]}, '-', {'$substr' : [{'$month' : '$timestamp'}, 0, 2]}, '-', {'$substr' : [{'$dayOfMonth' : '$timestamp'}, 0, 2]}] }, 'numberOfHits':'$numberOfHits'}}");
		
		//finally execute the grouping by day, adding the total number of hits
		BasicDBObject additionalOperation = new BasicDBObject("$group", (new BasicDBObject("_id",
				"$datePartDay").append("day", new BasicDBObject("$first","$datePartDay"))).append("numberOfHits", new BasicDBObject("$sum", "$numberOfHits")));

		Aggregation<? extends HistoryAggregateItem> aggregation = new Aggregation<HistoryAggregateItemImpl>(HistoryAggregateItemImpl.class, match, project,
				additionalOperation);

		
		AggregationResult<? extends HistoryAggregateItem> aggregationResult = coll.aggregate(aggregation);

		LOG.info("Aggregation result: " + aggregationResult.results().toString());
		
		return (List<HistoryAggregateItem>) aggregationResult.results();
				
	}	
	
	private List<HistoryItem> toAbstractListType(DBCursor<HistoryItemImpl> historyItems) {
		return toAbstractListType(historyItems.toArray());
	}

	private List<HistoryItem> toAbstractListType(List<HistoryItemImpl> historyItems) {
		final List<HistoryItem> result = Lists.newArrayListWithCapacity(historyItems.size());
		result.addAll(historyItems);
		LOG.info("Number of history items returned: " + result.size());
		return result;
	}

	@Override
	public void removeBefore(Date date) {
		coll.remove(new BasicDBObject("timestamp", new BasicDBObject("$lt", date)));
		
	}
}
