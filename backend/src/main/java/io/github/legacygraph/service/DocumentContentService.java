package io.github.legacygraph.service;

import io.github.legacygraph.extractors.DocumentExtractor;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class DocumentContentService {

    public String readText(String filePath) throws Exception {
        DocumentExtractor extractor = new DocumentExtractor();
        return extractor.extractText(new File(filePath));
    }
}
