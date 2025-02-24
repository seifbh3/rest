package fr.aba.prevoyance.api.service;

import com.aviva.prevoyance.dto.front.avenant.ControleAvantCreationDemandeAvenantFront;
import com.aviva.prevoyance.pivot.adhesion.contrat.avenant.DemandeAvenantPivot;
import com.aviva.prevoyance.pivot.adhesion.contrat.mouvement.MouvementPivot;
import com.aviva.prevoyance.pivot.adhesion.contrat.sinistre.SinistrePivot;
import com.aviva.prevoyance.pivot.v4.DossierPivot;
import com.aviva.prevoyance.pivot.v4.adhesion.contrat.ContratPivot;
import com.aviva.prevoyance.pivot.v4.garantie.GarantiePivot;
import com.aviva.prevoyance.pivot.v4.garantie.couverture.CouverturePivot;
import com.aviva.prevoyance.pivot.v4.professionel.ActiviteProfessionnellePivot;
import com.aviva.prevoyance.pivotparametrage.avenant.DemandeAvenantConstantes;
import com.aviva.prevoyance.pivotparametrage.enumere.GammeParam;
import com.aviva.prevoyance.pivotparametrage.produit.v2.ParametrageProduit;
import com.aviva.prevoyance.pivottechnical.exceptions.v2.ComponentControleException;
import com.aviva.prevoyance.pivottechnical.exceptions.v2.ComponentException;
import com.aviva.prevoyance.pivottechnical.exceptions.v2.ComponentLectureException;
import com.aviva.prevoyance.utils.api.component.EnvironnementComponent;
import com.aviva.prevoyance.utils.api.interceptor.UUIDTraceContextHolder;
import com.aviva.prevoyance.utils.component.LogComponent;
import com.aviva.prevoyance.utilsmicroservice.constante.ConstantesAutre;
import fr.aba.prevoyance.api.constantes.GarantieConstantes;
import fr.aba.prevoyance.api.enums.ValidateurType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.aviva.prevoyance.pivotparametrage.ContratConstantes.Statut.*;
import static com.aviva.prevoyance.pivotparametrage.ContratConstantes.Statut.PROPO_ACCEPTEE;

@RequiredArgsConstructor
@Service
@Slf4j
public class ControlService {

    @Value("${avenant.acces.autorise.referenceutilisateur}") private String referencesUtilisateursAutorisesAAvenanter;

    private static final String GESTIONNAIRE = "GESTIONNAIRE";
    private static final String INTERMEDIAIRE = "INTERMEDIAIRE";

    private static final String BLOCAGE_DEMANDE = "Vous n'avez pas la possibilité d'effectuer une demande de modification de garanties pour ce contrat.";

    private final LogComponent cLog;
    private final EnvironnementComponent cEnvironnement;

    private final DemandeAvenantService sDemandeAvenant;

    public void peutCalculerPrimesDeLaDemandeAvenant(DemandeAvenantPivot demandeAvenant) {
        demandeAvenantEstModifiable(Optional.ofNullable(demandeAvenant));
    }

    private void demandeAvenantEstModifiable(Optional<DemandeAvenantPivot> oDemandeAvenant) {
        demandeAvenantEstOuverte(oDemandeAvenant);
        demandeAvenantNEstPasLaPhoto(oDemandeAvenant);
    }

    private void demandeAvenantEstOuverte(Optional<DemandeAvenantPivot> oDemandeAvenant) {
        if (oDemandeAvenant.isPresent() && oDemandeAvenant.get().getEtatEnum().isPresent() && oDemandeAvenant.get().getEtatEnum().get().estModifiable()) {
            return;
        }
        throw new ComponentControleException("La demande d'avenant [" + (oDemandeAvenant.isPresent() ? oDemandeAvenant.get().getId() : "") + "] n'est pas modifiable");
    }

    private void demandeAvenantNEstPasLaPhoto(Optional<DemandeAvenantPivot> oDemandeAvenant) {
        if (oDemandeAvenant.isPresent() && oDemandeAvenant.get().getEtatEnum().isPresent() && !oDemandeAvenant.get().getEtatEnum().get().estUnePhoto()) {
            return;
        }
        throw new ComponentControleException("La demande d'avenant [" + (oDemandeAvenant.isPresent() ? oDemandeAvenant.get().getId() : "") + "] correspond à la photo");
    }

    public void verifierSiUtilisateurPeutCreerOuConsulterDemandeAvenant(final ControleAvantCreationDemandeAvenantFront controle, boolean consulationPossible) {
        if (controle == null) {
            throw new ComponentException("Une erreur est survenu lors du contrôle avant la création d'une demande avenant");
        }

        typeUtilisateurEstCorrect(controle.getTypeUtilisateur());
        intermediairePeutModifierLeContrat(controle.getDossier(), controle.getTypeUtilisateur(), controle.getRefUtilisateur());

        typeUtilisateurPeutCreerDemandeAvenantPourCeTypeDeProduit(controle.getTypeUtilisateur(), controle.getReferenceCommerciale(), controle.getParametrageProduit());
        if (controle.getGamme() == null) {
            throw new ComponentControleException("Problème pour identifier la gamme du produit du contrat [" + controle.getReferenceCommerciale() + "].");
        }

        List<GarantiePivot> garanties = controle.getDossier().getContrat().getInformations().getGaranties();
        if (!controle.getGamme().isNonConcerneesParLeControleDesClassesRisqueMultiplesPourCreerUneDemandeDAvenant()) {
            controlerClassesTarifaires(garanties, controle.getTypeUtilisateur());
        } else {
            controlerClassesTarifairesDifferentesEnCasDeDecesEnVigueur(garanties);
            controlerClassesTarifairesDifferentesEnCasDIncapaciteInvaliditeEnVigueur(garanties);
        }
        ActiviteProfessionnellePivot informationsProfessionnelles = null;

        if (controle.getDossier().getContrat() != null && controle.getDossier().getContrat().getAssure().isPresent()) {
            informationsProfessionnelles = controle.getDossier().getContrat().getAssure().get().getInformationsProfessionnelles();
        } else {
            throw new ComponentControleException("Une erreur est survenu lors de la récupération des informations professionnelles");
        }

        try {
            if (cEnvironnement.envApiIsLikeProd()) {
                blocageMisEnPlaceLeTempsDeTrouverUneSolution(controle.getTypeUtilisateur(), controle.getRefUtilisateur(), garanties, Optional.ofNullable(informationsProfessionnelles), UUIDTraceContextHolder.getUUIDTrace());
            }
        } catch (NullPointerException e) {
            log.error(e.getMessage());
        }
        peutCreerOuConsulterDemandeAvenant(controle, consulationPossible);
    }

    private void typeUtilisateurEstCorrect(String typeUtilisateur) {
        if (typeUtilisateur == null || !Arrays.asList(GESTIONNAIRE, INTERMEDIAIRE).contains(typeUtilisateur)) {
            throw new ComponentControleException("Ce type d'utilisateur [" + typeUtilisateur + "] n'est pas autorisé à créer une demande d'avenant.");
        }
    }

    private void intermediairePeutModifierLeContrat(DossierPivot dossier, String typeUtilisateur, String refUtilisateur) {
        if (StringUtils.equals(INTERMEDIAIRE, typeUtilisateur)
                && (dossier.getContrat().getIntermediaires().getIntermediaireActuel() == null || !StringUtils.equals(refUtilisateur, dossier.getContrat().getIntermediaires().getIntermediaireActuel().getReference()))) {
            cLog.formatLog(log, UUIDTraceContextHolder.getUUIDTrace(), Level.TRACE, "L'intermédiaire [" + refUtilisateur + "] ne possède pas le contrat [" + dossier.getContrat().getReferenceCommerciale() + "]");
            throw new ComponentControleException(BLOCAGE_DEMANDE);
        }
    }

    private void blocageMisEnPlaceLeTempsDeTrouverUneSolution(String typeUtilisateur, String refUtilisateur, List<com.aviva.prevoyance.pivot.v4.garantie.GarantiePivot> garanties, Optional<ActiviteProfessionnellePivot> oInfosProfessionelles, UUID uuidTrace) {
        controlerSurprimes(garanties, typeUtilisateur);
        controlerLeStatutProfessionnel(oInfosProfessionelles, uuidTrace);
        controlerSiSeulementQuelquesIntermediairesSontAutorises(typeUtilisateur, refUtilisateur, uuidTrace);
    }

    private void controlerSurprimes(List<com.aviva.prevoyance.pivot.v4.garantie.GarantiePivot> garanties, String typeUtilisateur) {
        List<BigDecimal> surprimeFixeTTC = garanties.stream().filter(Objects::nonNull).filter(g -> g.getCouvertures() != null && !g.getCouvertures().isEmpty())
                .flatMap(g -> g.getCouvertures().stream()).filter(c -> c.getProchainePrime() != null && c.getProchainePrime().getSurprime() != null)
                .map((com.aviva.prevoyance.pivot.v4.garantie.couverture.CouverturePivot c) -> c.getProchainePrime().getSurprime().getMontantTTCSurprimeFixe()).filter(Objects::nonNull)
                .filter(v -> v.compareTo(BigDecimal.valueOf(0)) > 0).distinct().collect(Collectors.toList());

        if (surprimeFixeTTC.isEmpty()) {
            return;
        }
        ValidateurType validateur = StringUtils.equals(GESTIONNAIRE, typeUtilisateur) ? ValidateurType.SUPRIME_FIXE_PRESENTE_GESTIONNAIRE : ValidateurType.SUPRIME_FIXE_PRESENTE_INTERMEDIAIRE;
        throw new ComponentControleException(validateur.getMessage());

    }

    private void controlerLeStatutProfessionnel(Optional<ActiviteProfessionnellePivot> oInfosProfessionelles, UUID uuidTrace) {
        if (oInfosProfessionelles.isPresent() && oInfosProfessionelles.get().getStatutProfessionel() != null
                && StringUtils.equals("MEDHOS", oInfosProfessionelles.get().getStatutProfessionel().getCode())) {
            cLog.formatLog(log, uuidTrace, Level.TRACE, "Blocage a cause du statut professionnel [" + oInfosProfessionelles.get().getStatutProfessionel().getCode() + "]");
            throw new ComponentControleException(BLOCAGE_DEMANDE);
        }
    }

    private void controlerSiSeulementQuelquesIntermediairesSontAutorises(String typeUtilisateur, String refUtilisateur, UUID uuidTrace) {
        if (StringUtils.equals(INTERMEDIAIRE, typeUtilisateur) && StringUtils.isNotBlank(referencesUtilisateursAutorisesAAvenanter)
                && !referencesUtilisateursAutorisesAAvenanter.contains("|" + refUtilisateur + "|")) {
            cLog.formatLog(log, uuidTrace, Level.TRACE, "Blocage a cause du matricule intermédiaire non autorisé [" + refUtilisateur + "]");
            throw new ComponentControleException(BLOCAGE_DEMANDE);
        }
    }

    private void typeUtilisateurPeutCreerDemandeAvenantPourCeTypeDeProduit(String typeUtilisateur, String refContrat, ParametrageProduit produit) {
        if (produit == null) {
            throw new ComponentControleException("Problème pour identifier leu produit du contrat [" + refContrat + "].");
        }
        if (StringUtils.equals(INTERMEDIAIRE, typeUtilisateur) && Boolean.TRUE.equals(produit.getAccesWprev().getDemandeAvenantPeutEtreInstruite())) {
            return;
        }
        if (StringUtils.equals(GESTIONNAIRE, typeUtilisateur) && Boolean.TRUE.equals(produit.getAccesWpop().getDemandeAvenantPeutEtreInstruite())) {
            return;
        }
        throw new ComponentControleException("Il n'est pas possible de réaliser un avenant avec cet outil pour le produit du contrat [" + refContrat + "].");
    }


    /**
     * Contrôle si différentes classes tarifaires sont présentes pour les garanties en vigueur. Utilise une condition constante qui renvoie toujours true.
     *
     * @param garanties       La liste des garanties à contrôler.
     * @param typeUtilisateur Le type de l'utilisateur pour déterminer le type de validateur.
     */
    private void controlerClassesTarifaires(List<com.aviva.prevoyance.pivot.v4.garantie.GarantiePivot> garanties, String typeUtilisateur) {
        ValidateurType validateur = StringUtils.equals(GESTIONNAIRE, typeUtilisateur) ? ValidateurType.CLASSES_TARIFAIRES_DIFFERENTES_GESTIONNAIRE
                : ValidateurType.CLASSES_TARIFAIRES_DIFFERENTES_INTERMEDIAIRE;
        controlerClassesTarifairesDifferentesEnVigueur(garanties, validateur, garantie -> true);
    }

    /**
     * Contrôle les classes tarifaires différentes en vigueur pour les garanties de décès.
     *
     * @param garanties La liste des garanties à contrôler.
     */
    private void controlerClassesTarifairesDifferentesEnCasDeDecesEnVigueur(List<com.aviva.prevoyance.pivot.v4.garantie.GarantiePivot> garanties) {
        controlerClassesTarifairesDifferentesEnVigueur(garanties, ValidateurType.DIFFERENTES_CLASSES_TARIFAIRES_EN_VIGUEUR, this::garantieEnCasDeDeces);
    }

    /**
     * Contrôle les classes tarifaires différentes en vigueur pour les garanties d'incapacité ou d'invalidité.
     *
     * @param garanties La liste des garanties à contrôler.
     */
    private void controlerClassesTarifairesDifferentesEnCasDIncapaciteInvaliditeEnVigueur(List<com.aviva.prevoyance.pivot.v4.garantie.GarantiePivot> garanties) {
        controlerClassesTarifairesDifferentesEnVigueur(garanties, ValidateurType.DIFFERENTES_CLASSES_TARIFAIRES_EN_VIGUEUR,
                garantie -> garantieEnCasDIncapacite(garantie) || garantieEnCasDInvalidite(garantie));
    }

    /**
     * Contrôle si différentes classes tarifaires sont présentes pour les garanties liées à un type spécifié en vigueur.
     *
     * @param garanties      La liste des garanties à contrôler.
     * @param validateurType Le type de validateur à utiliser en cas de classes tarifaires multiples.
     * @param estGarantie    La fonction de vérification du type de garantie.
     * @throws ComponentControleException Si différentes classes tarifaires sont détectées pour les garanties liées au type spécifié en vigueur.
     */
    private void controlerClassesTarifairesDifferentesEnVigueur(List<com.aviva.prevoyance.pivot.v4.garantie.GarantiePivot> garanties, ValidateurType validateurType,
                                                                Predicate<com.aviva.prevoyance.pivot.v4.garantie.GarantiePivot> estGarantie) {
        List<String> classesTarifaires = garanties.stream()
                .filter(GarantiePivot::estEnVigueur)
                .filter(estGarantie)
                .map(com.aviva.prevoyance.pivot.v4.garantie.GarantiePivot::getCouvertures)
                .flatMap(Collection::stream)
                .map(CouverturePivot::getClasseDeRisque)
                .filter(Objects::nonNull)
                .filter(s -> !s.isEmpty())
                .distinct().collect(Collectors.toList());
        if (classesTarifaires.size() > 1) {
            throw new ComponentControleException(validateurType.getMessage());
        }
    }

    /**
     * Vérifie si la garantie fournie couvre le cas de décès.
     *
     * @param garantie La garantie à vérifier.
     * @return true si la garantie couvre le cas de décès, sinon false.
     */
    private boolean garantieEnCasDeDeces(com.aviva.prevoyance.pivot.v4.garantie.GarantiePivot garantie) {
        return garantie != null && garantie.estDeTypePrestationDeces();
    }

    /**
     * Vérifie si la garantie fournie couvre le cas d'incapacité.
     *
     * @param garantie La garantie à vérifier.
     * @return true si la garantie couvre le cas d'incapacité, sinon false.
     */
    private boolean garantieEnCasDIncapacite(com.aviva.prevoyance.pivot.v4.garantie.GarantiePivot garantie) {
        return garantie != null && garantie.estDeTypePrestationIncapacite();
    }

    /**
     * Vérifie si la garantie fournie couvre le cas d'invalidité.
     *
     * @param garantie La garantie à vérifier.
     * @return true si la garantie couvre le cas d'invalidité, sinon false.
     */
    private boolean garantieEnCasDInvalidite(com.aviva.prevoyance.pivot.v4.garantie.GarantiePivot garantie) {
        return garantie != null && garantie.estDeTypePrestationInvalidite();
    }

    private void peutCreerOuConsulterDemandeAvenant(final ControleAvantCreationDemandeAvenantFront controle, boolean consulationPossible) {
        Map<ValidateurType, Predicate<DossierPivot>> validateurs = new LinkedHashMap<>();
        validateurs.put(ValidateurType.SANS_EFFET, presenceContratSansEffet());
        validateurs.put(ValidateurType.PROPOSITION_EN_ATTENTE, presencePropositionEnAttente());
        validateurs.put(ValidateurType.SANS_SUITE, presencePropositionSansSuite());
        validateurs.put(ValidateurType.DECES, presenceContratEnDeces());
        validateurs.put(ValidateurType.TERMINE, presenceContratTermine());
        validateurs.put(ValidateurType.CONTRAT_PAS_EN_VIGUEUR, contratNEstPasEnVigueur(controle.getCodesStatutsContratEnVigueur()));
        validateurs.put(ValidateurType.CONTRAT_PAS_EMIS, contratNEstPasEmis());
        validateurs.put(ValidateurType.ADHESION_IMPAYE, presenceAdhesionImpaye());
        validateurs.put(ValidateurType.PROFESSION_NON_RECUPEREE, absenceDInformationsProfessionelles());
        validateurs.forEach((type, predicate) -> {
            if (predicate.test(controle.getDossier())) {
                throw new ComponentControleException(type.getMessage());
            }
        });

        presenceDUnAvenantNonTraite(controle.getDossier().getContrat(), consulationPossible);
        presenceSinistreEnCours(controle.getDossier().getContrat());
        presenceMouvementsFuturs(controle.getReferenceCommerciale(), controle.getMouvementsFuturs());
        presenceDeCouvertureEnAttente(controle.getDossier().getContrat());
        presenceDeCouvertureRESansCodeFormule(controle.getDossier().getContrat(), controle.getGamme());
        presenceDesClassesTarifaireInterditesPourLaGamme(controle.getDossier().getContrat(), controle.getTypeUtilisateur(), controle.getGamme());
        coherenceStatutProAssureClasseTarifContrat(controle.getClassesStatutProfessionnel(), controle.getDossier(), controle.getGamme());
    }

    private Predicate<DossierPivot> presenceContratSansEffet() {
        return dossier -> SANS_EFFET.getCode().equals(dossier.getContrat().getStatut().getCode());
    }

    private Predicate<DossierPivot> presencePropositionEnAttente() {
        return dossier -> StringUtils.equalsAny(dossier.getContrat().getStatut().getCode(), PROPO_INCOMPLETE.getCode(), PROPO_A_ACCEPTER.getCode(), PROPO_ACCEPTEE.getCode());
    }

    private Predicate<DossierPivot> presencePropositionSansSuite() {
        return dossier -> SANS_SUITE.getCode().equals(dossier.getContrat().getStatut().getCode());
    }

    private Predicate<DossierPivot> presenceContratEnDeces() {
        return dossier -> DECES.getCode().equals(dossier.getContrat().getStatut().getCode());
    }

    private Predicate<DossierPivot> presenceContratTermine() {
        return dossier -> TERMINEE.getCode().equals(dossier.getContrat().getStatut().getCode());
    }

    private Predicate<DossierPivot> presenceAdhesionImpaye() {
        return dossier -> dossier.getContrat().getSituationComptable().getEstEnSituationDImpaye();
    }

    private Predicate<DossierPivot> presenceSinistreEnCoursV1() {
        return dossier -> CollectionUtils.isNotEmpty(dossier.getContrat().getSinistres());
    }

    private Predicate<DossierPivot> absenceDInformationsProfessionelles() {
        return dossier -> !dossier.getContrat().getAssure().isPresent() || dossier.getContrat().getAssure().get().getInformationsProfessionnelles() == null;
    }

    private Predicate<DossierPivot> contratNEstPasEnVigueur(List<String> codesStatutsContratEnVigueur) {
        return dossier -> {
            String codeStatut = dossier.getContrat().getStatut().getCode();
            return !codesStatutsContratEnVigueur.contains(codeStatut);
        };
    }

    private Predicate<DossierPivot> contratNEstPasEmis() {
        return dossier -> dossier.getContrat().getDatePriseEffet().isAfter(LocalDate.now());
    }

    private void presenceDUnAvenantNonTraite(ContratPivot contrat, boolean consulationPossible) {
        if (consulationPossible) {
            return;
        }
        fillDemandesAvenant(contrat);
        if (!contrat.getDemandesAvenants().isEmpty()) {
            List<DemandeAvenantPivot> demandesAvenantEnCours = contrat.getDemandesAvenants().stream().filter(Objects::nonNull).collect(Collectors.toList());

            if (!demandesAvenantEnCours.isEmpty()) {
                throw new ComponentControleException("Présence d'une demande d'avenant non modifiable et  sur le contrat");
            }
        }

    }

    private void presenceSinistreEnCours(ContratPivot contrat) {
        if (!contrat.getSinistres().isEmpty()) {
            List<SinistrePivot> sinistreEnCours = contrat.getSinistres().stream().filter(Objects::nonNull).filter(s -> (s.getEtat() != null && !s.getEtat().getStatut().isEstTerminal()))
                    .collect(Collectors.toList());

            if (!sinistreEnCours.isEmpty()) {
                throw new ComponentControleException("Présence d'un sinistre en cours sur le contrat");
            }
        }

    }

    private void presenceMouvementsFuturs(String referenceCommerciale, List<MouvementPivot> mouvementsFuturs) {
        Optional<DemandeAvenantPivot> oDemandeAvenant = sDemandeAvenant.getDemandeAvenantEnCours(referenceCommerciale);

        boolean contexteQuiNestPasUneDemandeAvenantEnCours = !oDemandeAvenant.isPresent();
        if ((contexteQuiNestPasUneDemandeAvenantEnCours || !extractEnumEtatDemandeAvenant(oDemandeAvenant.get()).mouvementsFutursEnPlace()) && !mouvementsFuturs.isEmpty()) {
            String pluriel = mouvementsFuturs.size() > 1 ? "s" : "";
            throw new ComponentControleException("Mouvement" + pluriel + " futur" + pluriel + " programmé" + pluriel + " sur ce contrat.");
        }


    }

    private void presenceDeCouvertureEnAttente(ContratPivot contrat) {
        Optional<DemandeAvenantPivot> oDemandeAvenant = sDemandeAvenant.getDemandeAvenantEnCours(contrat.getReferenceCommerciale());

        boolean contexteQuiNestPasUneDemandeAvenantEnCours = !oDemandeAvenant.isPresent();
        DemandeAvenantConstantes.EtatDemandeAvenant eEtat = contexteQuiNestPasUneDemandeAvenantEnCours ? null : extractEnumEtatDemandeAvenant(oDemandeAvenant.get());
        // formatter:off
        if ((contexteQuiNestPasUneDemandeAvenantEnCours || !presenceDemandeAvenantAnalyseInitierPourACRIOuPlusMaisPasTerminee(eEtat)) && contrat.auMoinsUneCouvertureEstEnAttente().isPresent()
                && Boolean.TRUE.equals(contrat.auMoinsUneCouvertureEstEnAttente().get())) {
            // formatter:on
            throw new ComponentControleException("Couverture(s) en attente d'émission");
        }
    }

    private void presenceDeCouvertureRESansCodeFormule(ContratPivot contrat, GammeParam gammeParam) {
        List<GarantiePivot> garanties = contrat.getInformations().getGaranties();
        if (gammeParam != null && StringUtils.equals(ConstantesAutre.Gammes.GERANT_MAJORITAIRE_2018.getCode(), gammeParam.getCode()) && CollectionUtils.isNotEmpty(garanties)) {
            garanties.stream().filter(garantie -> StringUtils.equals(GarantieConstantes.RE_GARANTIE, garantie.getCode())).map(GarantiePivot::getCouvertures).flatMap(List::stream).forEach(c -> {
                if (!c.getCodeFormule().isPresent()) {
                    throw new ComponentControleException("Il existe une couverture sans code formule");
                }
            });
        }
    }

    private DemandeAvenantConstantes.EtatDemandeAvenant extractEnumEtatDemandeAvenant(DemandeAvenantPivot demandeAvenant) {
        DemandeAvenantConstantes.EtatDemandeAvenant eEtat = null;
        try {
            eEtat = DemandeAvenantConstantes.EtatDemandeAvenant.getByCode(demandeAvenant.getEtat().getCode());
        } catch (NullPointerException e) {
            throw new ComponentControleException("Problème pour determiner l'état de la demande d'avenant");
        }
        return eEtat;
    }

    private boolean presenceDemandeAvenantAnalyseInitierPourACRIOuPlusMaisPasTerminee(DemandeAvenantConstantes.EtatDemandeAvenant eEtat) {
        return presenceDemandeAvenantSuivantUnEtatOuPlusMaisPasTerminee(DemandeAvenantConstantes.EtatDemandeAvenant.ANALYSE_INITIER_POUR_ACRI, eEtat);
    }

    private boolean presenceDemandeAvenantSuivantUnEtatOuPlusMaisPasTerminee(DemandeAvenantConstantes.EtatDemandeAvenant eEtatAVerifier, DemandeAvenantConstantes.EtatDemandeAvenant eEtatDemandeAvenant) {
        return eEtatDemandeAvenant.getOrdre() >= eEtatAVerifier.getOrdre() && !eEtatDemandeAvenant.estTerminal();
    }

    private void presenceDesClassesTarifaireInterditesPourLaGamme(ContratPivot contrat, String typeUtilisateur, GammeParam gammeParam) {
        if (gammeParam == null) {
            throw new ComponentControleException("Le code gamme n'a pas été identifié pour le contrôle de la présence des classes risque D ou 4");
        }
        if (!gammeParam.getClassesRisqueInterdites().isEmpty()) {
            long nombreClasseTarifaire4D = contrat.getInformations().getGaranties().stream()
                    .filter(g -> g.getCouvertures().stream().anyMatch(c -> gammeParam.getClassesRisqueInterdites().contains(c.getTarification().getCodeClasseDeRisque()))).count();
            if (!StringUtils.equals("", typeUtilisateur) && nombreClasseTarifaire4D > 0) {

                ValidateurType validateur = StringUtils.equals(GESTIONNAIRE, typeUtilisateur) ? ValidateurType.CLASSES_TARIFAIRES_4D_CODE_PRODUIT_SE4_GESTIONNAIRE
                        : ValidateurType.CLASSES_TARIFAIRES_4D_CODE_PRODUIT_SE4_INTERMEDIAIRE;
                throw new ComponentControleException(validateur.getMessage());
            }
        }
    }

    private void coherenceStatutProAssureClasseTarifContrat(List<String> classesStatutProfessionnel, DossierPivot dossier, GammeParam gammeParam) {
        if (gammeParam == null) {
            throw new ComponentControleException("Le code gamme n'a pas été identifié pour le contrôle de la cohérence classe / statut professionnel");
        }
        if (StringUtils.equals("SP1", gammeParam.getCode())) {
            return;
        }
        if (!dossier.getContrat().getAssure().isPresent() || dossier.getContrat().getAssure().get().getInformationsProfessionnelles() == null) {
            throw new ComponentControleException("L'assuré n'a pas été identifié pour le contrôle de la cohérence classe / statut professionnel");
        }
        if (dossier.getContrat().getAssure().isPresent() && dossier.getContrat().getAssure().get().getInformationsProfessionnelles().getStatutProfessionel() == null) {
            throw new ComponentControleException("Le statut professionnel de l'assuré n'a pas été identifié pour le contrôle de la cohérence classe / statut professionnel");
        }
        List<String> classesContrat = new ArrayList<>();
        List<CouverturePivot> couvertures = new ArrayList<>();

        // on récupère les couvertures du contrat
        dossier.getContrat().getInformations().getGaranties().stream().map(GarantiePivot::getCouvertures).collect(Collectors.toList()).forEach(couvertures::addAll);

        // on recupère les classes de risque
        couvertures.stream().map(c -> c.getTarification().getCodeClasseDeRisque()).forEach(classesContrat::add);

        // on récupère les classes possibles du statut professionnel de l'assuré
        ActiviteProfessionnellePivot activiteProfessionnelle = dossier.getContrat().getAssure().get().getInformationsProfessionnelles();
        if (activiteProfessionnelle != null) {
            classesContrat = classesContrat.stream().filter(Objects::nonNull).collect(Collectors.toList());

            if (!classesStatutProfessionnel.containsAll(classesContrat)) {
                throw new ComponentControleException("Le statut professionnel de l'assuré n'est pas cohérent avec les classes de risque des garanties.");
            }
        }
    }

    private void fillDemandeAvenantEnCours(ContratPivot contrat) {
        if (contrat.getDemandesAvenants() == null && StringUtils.isNotBlank(contrat.getReferenceCommerciale())) {
            Optional<DemandeAvenantPivot> oDemandeAvenant = sDemandeAvenant.getDemandeAvenantEnCours(contrat.getReferenceCommerciale());
            oDemandeAvenant.ifPresent(demandeAvenantPivot -> contrat.setDemandesAvenants(Collections.singletonList(demandeAvenantPivot)));
        }
    }

    private void fillDemandesAvenant(ContratPivot contrat) {
        if (contrat.getDemandesAvenants() == null && StringUtils.isNotBlank(contrat.getReferenceCommerciale())) {
            contrat.setDemandesAvenants(sDemandeAvenant.getDemandesAvenantsEnCoursNonModifiablesMaisAnnulables(contrat.getReferenceCommerciale()));
        }
        if (contrat.getDemandesAvenants() == null) {
            throw new ComponentLectureException("Problème pour récuperer les demandes d'avenant du contrat [" + contrat.getReferenceCommerciale() + "]");
        }

    }
}