import { useSearchParams } from 'react-router-dom';
import { PollingMode } from 'generated-sources';
import { useEffect } from 'react';
import { Option } from 'react-multi-select-component';
import { MessagesFilterKeys } from 'lib/constants';

import { convertStrToPollingMode, ModeOptions } from './filterUtils';
import {
  AdvancedFilter,
  selectFilter,
  useMessageFiltersStore,
} from './useMessageFiltersStore';
import { useMessagesFiltersFields } from './useMessagesFiltersFields';

const PER_PAGE = 100;

const defaultModeValue = ModeOptions[0].value;

export function useRefreshData(initSearchParams?: URLSearchParams) {
  const [, setSearchParams] = useSearchParams(initSearchParams);
  return () => {
    setSearchParams((params) => {
      if (params.get(MessagesFilterKeys.r)) {
        params.delete(MessagesFilterKeys.r);
      } else {
        params.set(MessagesFilterKeys.r, 'r');
      }

      return params;
    });
  };
}

export function getCursorValue(urlSearchParam: URLSearchParams) {
  const cursor = parseInt(
    urlSearchParam.get(MessagesFilterKeys.cursor) || '0',
    10
  );

  if (Number.isNaN(cursor)) {
    return 0;
  }

  return cursor;
}

export function usePaginateTopics(initSearchParams?: URLSearchParams) {
  const [, setSearchParams] = useSearchParams(initSearchParams);
  return () => {
    setSearchParams((params) => {
      const cursor = getCursorValue(params) + 1;

      if (cursor) {
        params.set(MessagesFilterKeys.cursor, cursor.toString());
      }

      return params;
    });
  };
}

export function useMessagesFilters(topicName: string) {
  const [searchParams, setSearchParams] = useSearchParams();
  const refreshData = useRefreshData(searchParams);
  const {
    initMessagesFiltersFields,
    setMessagesFiltersField,
    removeMessagesFiltersField,
  } = useMessagesFiltersFields(topicName);

  useEffect(() => {
    setSearchParams((params) => {
      initMessagesFiltersFields(params);
      params.set(MessagesFilterKeys.limit, PER_PAGE.toString());

      if (!params.get(MessagesFilterKeys.mode)) {
        params.set(MessagesFilterKeys.mode, defaultModeValue);
      }

      if (params.get(MessagesFilterKeys.activeFilterNPId)) {
        params.delete(MessagesFilterKeys.activeFilterNPId);
        params.delete(MessagesFilterKeys.smartFilterId);
      }

      params.delete(MessagesFilterKeys.cursor);

      return params;
    });
  }, []);

  /**
   * @description
   * Params getter
   * */
  const mode =
    convertStrToPollingMode(searchParams.get(MessagesFilterKeys.mode) || '') ||
    defaultModeValue;

  const dateParams = searchParams.get(MessagesFilterKeys.timestamp);

  const date = dateParams ? new Date(parseFloat(dateParams)) : null;

  const keySerde = searchParams.get(MessagesFilterKeys.keySerde) || undefined;
  const valueSerde =
    searchParams.get(MessagesFilterKeys.valueSerde) || undefined;

  const offset = searchParams.get(MessagesFilterKeys.offset) || undefined;

  const search = searchParams.get(MessagesFilterKeys.stringFilter) || '';

  const partitions = (searchParams.get(MessagesFilterKeys.partitions) || '')
    .split(',')
    .filter((v) => v);

  const smartFilterId =
    searchParams.get(MessagesFilterKeys.activeFilterId) ||
    searchParams.get(MessagesFilterKeys.activeFilterNPId) ||
    '';

  const smartFilter = useMessageFiltersStore(selectFilter(smartFilterId));

  /**
   * @description
   * Params setters
   * */
  const setMode = (newMode: PollingMode) => {
    setSearchParams((params) => {
      removeMessagesFiltersField(MessagesFilterKeys.offset);
      removeMessagesFiltersField(MessagesFilterKeys.timestamp);
      setMessagesFiltersField(MessagesFilterKeys.mode, newMode);
      params.set(MessagesFilterKeys.mode, newMode);
      params.delete(MessagesFilterKeys.offset);
      params.delete(MessagesFilterKeys.timestamp);
      return params;
    });
  };

  const setTimeStamp = (newDate: Date | null) => {
    if (newDate === null) {
      setSearchParams((params) => {
        removeMessagesFiltersField(MessagesFilterKeys.timestamp);
        params.delete(MessagesFilterKeys.timestamp);
        return params;
      });
      return;
    }

    setSearchParams((params) => {
      setMessagesFiltersField(
        MessagesFilterKeys.timestamp,
        newDate.getTime().toString()
      );
      params.set(MessagesFilterKeys.timestamp, newDate.getTime().toString());
      return params;
    });
  };

  const setKeySerde = (newKeySerde: string) => {
    setSearchParams((params) => {
      params.set(MessagesFilterKeys.keySerde, newKeySerde);
      setMessagesFiltersField(MessagesFilterKeys.keySerde, newKeySerde);
      return params;
    });
  };

  const setValueSerde = (newValueSerde: string) => {
    setSearchParams((params) => {
      setMessagesFiltersField(MessagesFilterKeys.valueSerde, newValueSerde);
      params.set(MessagesFilterKeys.valueSerde, newValueSerde);
      return params;
    });
  };

  const setOffsetValue = (newOffsetValue: string) => {
    setSearchParams((params) => {
      setMessagesFiltersField(MessagesFilterKeys.offset, newOffsetValue);
      params.set(MessagesFilterKeys.offset, newOffsetValue);
      return params;
    });
  };

  const setSearch = (value: string) => {
    setSearchParams((params) => {
      if (value) {
        setMessagesFiltersField(MessagesFilterKeys.stringFilter, value);
        params.set(MessagesFilterKeys.stringFilter, value);
      } else {
        removeMessagesFiltersField(MessagesFilterKeys.stringFilter);
        params.delete(MessagesFilterKeys.stringFilter);
      }
      return params;
    });
  };

  const setPartition = (values: Option[]) => {
    setSearchParams((params) => {
      params.delete(MessagesFilterKeys.partitions);

      if (values.length) {
        setMessagesFiltersField(
          MessagesFilterKeys.partitions,
          values.map((v) => v.value).join(',')
        );
        params.append(
          MessagesFilterKeys.partitions,
          values.map((v) => v.value).join(',')
        );
      } else {
        removeMessagesFiltersField(MessagesFilterKeys.partitions);
      }

      return params;
    });
  };

  const setSmartFilter = (
    newFilter: AdvancedFilter | null,
    persisted = true
  ) => {
    if (newFilter === null) {
      setSearchParams((params) => {
        params.delete(MessagesFilterKeys.smartFilterId);
        params.delete(MessagesFilterKeys.activeFilterId);
        params.delete(MessagesFilterKeys.activeFilterNPId);
        return params;
      });
      return;
    }

    const { id } = newFilter;
    // callback should always capture the latest states not rely on rendering

    const filter = selectFilter(newFilter.id)(
      useMessageFiltersStore.getState()
    );

    // setting something that is not in the state
    if (!filter) return;

    setSearchParams((params) => {
      params.set(MessagesFilterKeys.smartFilterId, filter.filterCode);
      params.set(
        persisted
          ? MessagesFilterKeys.activeFilterId
          : MessagesFilterKeys.activeFilterNPId,
        id
      );
      return params;
    });
  };

  return {
    mode,
    setMode,
    date,
    setTimeStamp,
    keySerde,
    setKeySerde,
    valueSerde,
    setValueSerde,
    offset,
    setOffsetValue,
    search,
    setSearch,
    partitions,
    setPartition,
    smartFilter,
    setSmartFilter,
    refreshData,
  };
}

export function useIsMessagesSmartFilterPersisted(
  initSearchParams?: URLSearchParams
) {
  const [searchParams] = useSearchParams(initSearchParams);

  return !!searchParams.get(MessagesFilterKeys.activeFilterId);
}

export function useIsLiveMode(initSearchParams?: URLSearchParams) {
  const [searchParams] = useSearchParams(initSearchParams);

  return (
    convertStrToPollingMode(searchParams.get(MessagesFilterKeys.mode) || '') ===
    PollingMode.TAILING
  );
}
