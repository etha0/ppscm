<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<div data-history-fragment>
<div class="notice"><strong><c:out value="${historyProduct.name}"/></strong> · <c:out value="${historyProduct.code}"/> · 현재 재고 ${historyProduct.stock}개</div>
<p class="history-help">등록 순서로 최신 이력부터 100건씩 표시합니다.</p>
<div class="table-scroll" tabindex="0" role="region" aria-label="상품별 재고 이력 표"><table class="history-table"><thead><tr><th>처리일</th><th>등록일</th><th>구분</th><th>변동</th><th>변경 전</th><th>변경 후</th><th>사유</th><th>담당자</th></tr></thead><tbody>
<c:forEach items="${historyEvents}" var="e"><tr><td><c:out value="${e.occurred_on}"/></td><td><c:out value="${e.created_at}"/></td><td><c:out value="${e.kind}"/></td><td>${e.delta > 0 ? '+' : ''}${e.delta}</td><td>${e.before_stock}</td><td>${e.after_stock}</td><td class="history-note"><c:out value="${e.note}"/></td><td><c:out value="${e.actor_name}"/></td></tr></c:forEach>
</tbody></table></div>
<c:if test="${empty historyEvents}"><p class="history-help">등록된 재고 변동 이력이 없습니다.</p></c:if>
<div class="history-pagination"><c:if test="${not empty param.before}"><button type="button" class="button" data-history-url="${pageContext.request.contextPath}/app/inventory-history?id=${historyProduct.id}">최신 이력</button></c:if><c:if test="${historyMore}"><button type="button" class="button" data-history-url="${pageContext.request.contextPath}/app/inventory-history?id=${historyProduct.id}&amp;before=${historyCursor}">이전 이력 100건</button></c:if></div>
</div>
