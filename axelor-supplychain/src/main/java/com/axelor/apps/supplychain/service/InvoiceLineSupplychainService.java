/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2018 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.supplychain.service;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.InvoiceLineRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.service.AccountManagementAccountService;
import com.axelor.apps.account.service.AnalyticMoveLineService;
import com.axelor.apps.account.service.app.AppAccountService;
import com.axelor.apps.account.service.invoice.InvoiceLineServiceImpl;
import com.axelor.apps.account.service.invoice.InvoiceToolService;
import com.axelor.apps.account.service.invoice.generator.InvoiceLineGenerator;
import com.axelor.apps.base.db.PriceList;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.db.repo.AppBaseRepository;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.service.CurrencyService;
import com.axelor.apps.base.service.PriceListService;
import com.axelor.apps.base.service.tax.AccountManagementService;
import com.axelor.apps.purchase.db.SupplierCatalog;
import com.axelor.apps.purchase.db.repo.SupplierCatalogRepository;
import com.axelor.apps.purchase.service.PurchaseProductService;
import com.axelor.apps.sale.db.PackLine;
import com.axelor.apps.sale.service.app.AppSaleService;
import com.axelor.apps.supplychain.service.app.AppSupplychainService;
import com.axelor.apps.supplychain.service.invoice.generator.InvoiceLineGeneratorSupplyChain;
import com.axelor.exception.AxelorException;
import com.axelor.inject.Beans;
import com.google.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InvoiceLineSupplychainService extends InvoiceLineServiceImpl {

  protected PurchaseProductService purchaseProductService;

  @Inject private AppSupplychainService appSupplychainService;

  @Inject private AppSaleService appSaleService;

  @Inject
  public InvoiceLineSupplychainService(
      AccountManagementService accountManagementService,
      CurrencyService currencyService,
      PriceListService priceListService,
      AppAccountService appAccountService,
      AnalyticMoveLineService analyticMoveLineService,
      AccountManagementAccountService accountManagementAccountService,
      PurchaseProductService purchaseProductService) {

    super(
        accountManagementService,
        currencyService,
        priceListService,
        appAccountService,
        analyticMoveLineService,
        accountManagementAccountService);
    this.purchaseProductService = purchaseProductService;
  }

  @Override
  public Unit getUnit(Product product, boolean isPurchase) {
    if (isPurchase) {
      if (product.getPurchasesUnit() != null) {
        return product.getPurchasesUnit();
      } else {
        return product.getUnit();
      }
    } else {
      if (product.getSalesUnit() != null) {
        return product.getPurchasesUnit();
      } else {
        return product.getUnit();
      }
    }
  }

  @Override
  public Map<String, Object> getDiscount(
      Invoice invoice, InvoiceLine invoiceLine, BigDecimal price) {

    PriceList priceList = invoice.getPriceList();
    BigDecimal discountAmount = BigDecimal.ZERO;
    int computeMethodDiscountSelect =
        appAccountService.getAppBase().getComputeMethodDiscountSelect();

    Map<String, Object> discounts = super.getDiscount(invoice, invoiceLine, price);

    if (priceList != null) {
      discountAmount = (BigDecimal) discounts.get("discountAmount");
    }

    if (invoice.getOperationTypeSelect() < InvoiceRepository.OPERATION_TYPE_CLIENT_SALE
        && discountAmount.compareTo(BigDecimal.ZERO) == 0) {
      List<SupplierCatalog> supplierCatalogList = invoiceLine.getProduct().getSupplierCatalogList();
      if (supplierCatalogList != null && !supplierCatalogList.isEmpty()) {
        SupplierCatalog supplierCatalog =
            Beans.get(SupplierCatalogRepository.class)
                .all()
                .filter(
                    "self.product = ?1 AND self.minQty <= ?2 AND self.supplierPartner = ?3 ORDER BY self.minQty DESC",
                    invoiceLine.getProduct(),
                    invoiceLine.getQty(),
                    invoice.getPartner())
                .fetchOne();
        if (supplierCatalog != null) {

          discounts = purchaseProductService.getDiscountsFromCatalog(supplierCatalog, price);

          if (computeMethodDiscountSelect != AppBaseRepository.DISCOUNT_SEPARATE) {
            discounts.put(
                "price",
                priceListService.computeDiscount(
                    price,
                    (int) discounts.get("discountTypeSelect"),
                    (BigDecimal) discounts.get("discountAmount")));
          }
        }
      }
    }
    return discounts;
  }

  @Override
  public Map<String, Object> fillPriceAndAccount(
      Invoice invoice, InvoiceLine invoiceLine, boolean isPurchase) throws AxelorException {

    try {
      return super.fillPriceAndAccount(invoice, invoiceLine, isPurchase);
    } catch (AxelorException e) {
      if (checkTaxRequired(invoiceLine, invoiceLine.getPackPriceSelect())) {
        throw e;
      } else {
        Map<String, Object> productInformation = new HashMap<>();
        productInformation.put("taxLine", null);
        productInformation.put("taxRate", BigDecimal.ZERO);
        productInformation.put("taxCode", null);
        productInformation.put("taxEquiv", null);
        productInformation.put("account", null);
        productInformation.put("discountAmount", BigDecimal.ZERO);
        productInformation.put("discountTypeSelect", 0);
        productInformation.put("price", BigDecimal.ZERO);
        return productInformation;
      }
    }
  }

  public boolean checkTaxRequired(InvoiceLine invoiceLine, Integer packPriceSelect) {

    if (appSupplychainService.getAppSupplychain().getActive()
        && appSaleService.getAppSale().getProductPackMgt()) {

      if (invoiceLine.getIsSubLine() && packPriceSelect == InvoiceLineRepository.PACK_PRICE_ONLY) {
        return false;
      }
      if (invoiceLine.getTypeSelect() == InvoiceLineRepository.TYPE_PACK
          && packPriceSelect == InvoiceLineRepository.SUBLINE_PRICE_ONLY) {
        return false;
      }
    }

    return true;
  }

  @Override
  public Map<String, Object> fillProductInformation(Invoice invoice, InvoiceLine invoiceLine)
      throws AxelorException {

    Map<String, Object> productInformation = new HashMap<>();

    boolean isPurchase = InvoiceToolService.isPurchase(invoice);
    Integer sequence = invoiceLine.getSequence();
    if (sequence == null) {
      sequence = 0;
    }

    if (sequence == 0 && invoice.getInvoiceLineList() != null) {
      sequence = invoice.getInvoiceLineList().size();
      invoiceLine.setSequence(sequence);
    }

    if (appSupplychainService.getAppSupplychain().getActive()
        && appSaleService.getAppSale().getProductPackMgt()
        && invoiceLine.getProduct() != null
        && invoiceLine
            .getProduct()
            .getProductTypeSelect()
            .equals(ProductRepository.PRODUCT_TYPE_PACK)
        && invoiceLine.getProduct().getPackLines() != null) {

      List<InvoiceLine> subLineList = new ArrayList<>();
      Integer packPriceSelect = invoiceLine.getProduct().getPackPriceSelect();
      invoiceLine.setTypeSelect(InvoiceLineRepository.TYPE_PACK);
      invoiceLine.setPackPriceSelect(packPriceSelect);
      for (PackLine packLine : invoiceLine.getProduct().getPackLines()) {

        InvoiceLine subLine = new InvoiceLine();
        subLine.setProduct(packLine.getProduct());
        subLine.setUnit(this.getUnit(packLine.getProduct(), isPurchase));
        subLine.setProductName(packLine.getProduct().getName());
        subLine.setQty(new BigDecimal(packLine.getQuantity()));
        subLine.setIsSubLine(true);
        subLine.setPackPriceSelect(packPriceSelect);
        String description = null;
        if (appAccountService.getAppInvoice().getIsEnabledProductDescriptionCopy()) {
          description = invoiceLine.getProduct().getDescription();
        }
        Map<String, Object> accountInfo = super.fillPriceAndAccount(invoice, subLine, isPurchase);
        subLine.setAccount((Account) accountInfo.get("account"));
        if (packPriceSelect != InvoiceLineRepository.PACK_PRICE_ONLY) {
          subLine.setPrice((BigDecimal) accountInfo.get("price"));
          subLine.setInTaxPrice((BigDecimal) accountInfo.get("inTaxPrice"));
          subLine.setPriceDiscounted(computeDiscount(subLine, invoice.getInAti()));
        }

        int discountTypeSelect = 0;
        if (accountInfo.get("discountTypeSelect") != null) {
          discountTypeSelect = (Integer) accountInfo.get("discountTypeSelect");
        }

        BigDecimal qty = new BigDecimal(packLine.getQuantity());

        if (invoiceLine.getQty() != null) {
          qty = qty.multiply(invoiceLine.getQty()).setScale(2, RoundingMode.HALF_EVEN);
        }

        InvoiceLineGenerator invoiceLineGenerator =
            new InvoiceLineGeneratorSupplyChain(
                invoice,
                subLine.getProduct(),
                subLine.getProductName(),
                subLine.getPrice(),
                subLine.getInTaxPrice(),
                subLine.getPriceDiscounted(),
                description,
                qty,
                subLine.getUnit(),
                (TaxLine) accountInfo.get("taxLine"),
                ++sequence,
                (BigDecimal) accountInfo.get("discountAmount"),
                discountTypeSelect,
                null,
                null,
                false,
                null,
                null,
                null,
                true,
                packPriceSelect) {

              @Override
              public List<InvoiceLine> creates() throws AxelorException {

                InvoiceLine invoiceLine = this.createInvoiceLine();
                invoiceLine.setInvoice(null); //Enable line to be added on main o2m
                List<InvoiceLine> lines = new ArrayList<>();
                lines.add(invoiceLine);

                return lines;
              }
            };
        subLineList.addAll(invoiceLineGenerator.creates());
      }
      productInformation.put("typeSelect", InvoiceLineRepository.TYPE_PACK);
      productInformation.put("packPriceSelect", packPriceSelect);
      productInformation.put("subLineList", subLineList);
    } else {
      productInformation.put("typeSelect", InvoiceLineRepository.TYPE_NORMAL);
      productInformation.put("packPriceSelect", InvoiceLineRepository.PACK_PRICE_ONLY);
      productInformation.put("subLineList", null);
      productInformation.put("totalPack", BigDecimal.ZERO);
      invoiceLine.setTypeSelect(InvoiceLineRepository.TYPE_NORMAL);
    }

    productInformation.putAll(super.fillProductInformation(invoice, invoiceLine));

    return productInformation;
  }

  @Override
  public boolean isAccountRequired(InvoiceLine invoiceLine) {

    if (appSaleService.getAppSale().getProductPackMgt()) {

      if (invoiceLine.getIsSubLine()
          && invoiceLine.getPackPriceSelect() == InvoiceLineRepository.PACK_PRICE_ONLY) {
        return false;
      }
      if (invoiceLine.getTypeSelect() == InvoiceLineRepository.TYPE_PACK
          && invoiceLine.getPackPriceSelect() == InvoiceLineRepository.SUBLINE_PRICE_ONLY) {
        return false;
      }
    }

    return true;
  }
}
