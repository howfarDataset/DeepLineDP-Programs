/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.wicket.request.mapper;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.wicket.request.IRequestHandler;
import org.apache.wicket.request.IRequestMapper;
import org.apache.wicket.request.Request;
import org.apache.wicket.request.Url;


/**
* Thread safe compound {@link IRequestMapper}. The mappers are searched depending on their
* compatibility score and the orders they were registered. If two or more {@link IRequestMapper}s
* have the same compatibility score, the last registered mapper has highest priority.
*
* @author igor.vaynberg
* @author Matej Knopp
*/
public class CompoundRequestMapper implements ICompoundRequestMapper
{
/**
*
*/
private static class MapperWithScore implements Comparable<MapperWithScore>
{
private final IRequestMapper mapper;
private final int compatibilityScore;

public MapperWithScore(final IRequestMapper mapper, final int compatibilityScore)
{
this.mapper = mapper;
this.compatibilityScore = compatibilityScore;
}

public int compareTo(final MapperWithScore o)
{
return o.compatibilityScore - compatibilityScore;
}

public IRequestMapper getMapper()
{
return mapper;
}

/**
* @see java.lang.Object#toString()
*/
@Override
public String toString()
{
return "Mapper: " + mapper.getClass().getName() + "; Score: " + compatibilityScore;
}
}

private final List<IRequestMapper> mappers = new CopyOnWriteArrayList<IRequestMapper>();

/**
* Construct.
*/
public CompoundRequestMapper()
{
}

/**
* @see org.apache.wicket.request.mapper.ICompoundRequestMapper#add(org.apache.wicket.request.IRequestMapper)
*/
public CompoundRequestMapper add(final IRequestMapper mapper)
{
mappers.add(0, mapper);
return this;
}

/**
* @see org.apache.wicket.request.mapper.ICompoundRequestMapper#remove(org.apache.wicket.request.IRequestMapper)
*/
public CompoundRequestMapper remove(final IRequestMapper mapper)
{
mappers.remove(mapper);
return this;
}

/**
* Searches the registered {@link IRequestMapper}s to find one that can map the {@link Request}.
* Each registered {@link IRequestMapper} is asked to provide its compatibility score. Then the
* mappers are asked to map the request in order depending on the provided compatibility
* score.
* <p>
* The mapper with highest compatibility score which can map the request is returned.
*
* @param request
* @return RequestHandler for the request or <code>null</code> if no mapper for the request is
*         found.
*/
public IRequestHandler mapRequest(final Request request)
{
List<MapperWithScore> list = new ArrayList<MapperWithScore>(mappers.size());

for (IRequestMapper mapper : mappers)
{
int score = mapper.getCompatibilityScore(request);
list.add(new MapperWithScore(mapper, score));
}

Collections.sort(list);

for (MapperWithScore mapperWithScore : list)
{
IRequestHandler handler = mapperWithScore.getMapper().mapRequest(request);
if (handler != null)
{
return handler;
}
}

return null;
}

/**
* Searches the registered {@link IRequestMapper}s to find one that can map the
* {@link IRequestHandler}. Each registered {@link IRequestMapper} is asked to map the
* {@link IRequestHandler} until a mapper which can map the {@link IRequestHandler} is found or
* no more mappers are left.
* <p>
* The mappers are searched in reverse order as they have been registered. More recently
* registered mappers have bigger priority.
*
* @param handler
* @return Url for the handler or <code>null</code> if no mapper for the handler is found.
*/
public Url mapHandler(final IRequestHandler handler)
{
for (IRequestMapper mapper : mappers)
{
Url url = mapper.mapHandler(handler);
if (url != null)
{
return url;
}
}
return null;
}

/**
* The scope of the compound mapper is the highest score of the registered mappers.
*
* {@inheritDoc}
*/
public int getCompatibilityScore(final Request request)
{
int score = Integer.MIN_VALUE;
for (IRequestMapper mapper : mappers)
{
score = Math.max(score, mapper.getCompatibilityScore(request));
}
return score;
}

public Iterator<IRequestMapper> iterator()
{
return mappers.iterator();
}

public void unmount(String path)
{
final Url url = Url.parse(path);
final Request request = createRequest(url);

for (Iterator<IRequestMapper> itor = iterator(); itor.hasNext();)
{
IRequestMapper mapper = itor.next();
if (mapper.mapRequest(request) != null)
{
remove(mapper);
}
}
}

int size()
{
return mappers.size();
}

Request createRequest(final Url url)
{
Request request = new Request()
{
@Override
public Url getUrl()
{
return url;
}

@Override
public Locale getLocale()
{
return null;
}

@Override
public Object getContainerRequest()
{
return null;
}

@Override
public Url getClientUrl()
{
return null;
}

@Override
public Charset getCharset()
{
return null;
}
};
return request;
}
}