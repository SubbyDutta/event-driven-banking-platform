package backend.backend.service;

import backend.backend.Dtos.BuisnessLoggingResponseDto;
import backend.backend.model.BuisnessLog;
import backend.backend.repository.BuisnessLoggingRepository;
import backend.backend.requests_response.PagedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@RequiredArgsConstructor
@Service
public class BuisnessLoggingService {
    private final CachedLists cachedLists;
    private final BuisnessLoggingRepository buisnessLoggingRepository;

    @Caching(evict = {
            @CacheEvict(value = "banking:logs:list", allEntries = true),
            @CacheEvict(value = "banking:logs:byaction", allEntries = true)
    })
    public void log(String action, String username, String details) {
        BuisnessLog log = new BuisnessLog(action, username, details, Instant.now());
        buisnessLoggingRepository.save(log);
    }
    public PagedResponse<BuisnessLoggingResponseDto> getBuisnessLogsByAction(int page, int size,String action) {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (size > 100) size = 100;

        List<BuisnessLoggingResponseDto> content =cachedLists.getBuisnessLogs(action,page,size) ;
        long totalElements = buisnessLoggingRepository.count();
        int totalPages = (int) Math.ceil((double) totalElements / size);

        return new PagedResponse<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                page + 1 >= totalPages
        );
    }
    public PagedResponse<BuisnessLoggingResponseDto> getAllLogs(int page,int size)
    {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (size > 100) size = 100;
        List<BuisnessLoggingResponseDto> content =cachedLists.getAllLogs(page,size);
        long totalElements = buisnessLoggingRepository.count();
        int totalPages = (int) Math.ceil((double) totalElements / size);

        return new PagedResponse<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                page + 1 >= totalPages
        );
    }
    }
