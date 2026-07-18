package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence;

import io.github.yanhuo218.autumnwind.modelregistry.domain.model.InputModality;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelCapabilities;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.OutputModality;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "model_capabilities", schema = "model_registry")
public class ModelCapabilityEntity {

    @Id
    @Column(name = "model_id")
    private UUID modelId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id")
    private ModelEntity model;

    @Column(name = "text_input", nullable = false)
    private boolean textInput;

    @Column(name = "image_input", nullable = false)
    private boolean imageInput;

    @Column(name = "file_input", nullable = false)
    private boolean fileInput;

    @Column(name = "video_input", nullable = false)
    private boolean videoInput;

    @Enumerated(EnumType.STRING)
    @Column(name = "output_modality", nullable = false, length = 16)
    private OutputModality outputModality;

    @Column(nullable = false)
    private boolean streaming;

    @Column(name = "system_prompt", nullable = false)
    private boolean systemPrompt;

    @Column(nullable = false)
    private boolean reasoning;

    @Column(name = "context_length", nullable = false)
    private int contextLength;

    @Column(name = "max_output_length", nullable = false)
    private int maxOutputLength;

    protected ModelCapabilityEntity() {
    }

    static ModelCapabilityEntity create(ModelEntity model, ModelCapabilities capabilities) {
        ModelCapabilityEntity entity = new ModelCapabilityEntity();
        entity.model = model;
        entity.update(capabilities);
        return entity;
    }

    void update(ModelCapabilities capabilities) {
        Set<InputModality> inputs = capabilities.inputModalities();
        textInput = inputs.contains(InputModality.TEXT);
        imageInput = inputs.contains(InputModality.IMAGE);
        fileInput = inputs.contains(InputModality.FILE);
        videoInput = inputs.contains(InputModality.VIDEO);
        outputModality = capabilities.outputModality();
        streaming = capabilities.streaming();
        systemPrompt = capabilities.systemPrompt();
        reasoning = capabilities.reasoning();
        contextLength = capabilities.contextLength();
        maxOutputLength = capabilities.maxOutputLength();
    }

    ModelCapabilities toCapabilities() {
        EnumSet<InputModality> inputs = EnumSet.noneOf(InputModality.class);
        if (textInput) {
            inputs.add(InputModality.TEXT);
        }
        if (imageInput) {
            inputs.add(InputModality.IMAGE);
        }
        if (fileInput) {
            inputs.add(InputModality.FILE);
        }
        if (videoInput) {
            inputs.add(InputModality.VIDEO);
        }
        return new ModelCapabilities(
                model.interfaceType(),
                inputs,
                outputModality,
                streaming,
                systemPrompt,
                reasoning,
                contextLength,
                maxOutputLength
        );
    }
}
