/**
 * Pisces A/B测试 JavaScript SDK
 * 无需用户认证，使用visitorId即可
 * 
 * 使用方法：
 * const pisces = new PiscesSDK({
 *   apiBaseUrl: 'http://localhost:8080/api',
 *   experimentId: 'exp_price_001',
 *   visitorId: getVisitorId()
 * });
 */
class PiscesSDK {
  constructor(config) {
    this.apiBaseUrl = config.apiBaseUrl || 'http://localhost:8080/api';
    this.experimentId = config.experimentId;
    this.visitorId = config.visitorId;
    
    // 缓存
    this.groupIdCache = null;
    this.experimentConfigCache = null;
  }

  /**
   * 获取访客所在的实验组
   * @returns {Promise<string>} 实验组ID
   */
  async getGroup() {
    if (this.groupIdCache) {
      return this.groupIdCache;
    }
    
    try {
      const response = await fetch(`${this.apiBaseUrl}/traffic/assign`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          experimentId: this.experimentId,
          visitorId: this.visitorId
        })
      });
      
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }
      
      const data = await response.json();
      this.groupIdCache = data.data;
      return data.data;
    } catch (error) {
      console.error('获取实验组失败:', error);
      return null;
    }
  }

  /**
   * 获取实验配置
   * @returns {Promise<object>} 实验配置
   */
  async getExperimentConfig() {
    if (this.experimentConfigCache) {
      return this.experimentConfigCache;
    }
    
    try {
      const response = await fetch(`${this.apiBaseUrl}/experiments/${this.experimentId}`);
      const data = await response.json();
      this.experimentConfigCache = data.data;
      return data.data;
    } catch (error) {
      console.error('获取实验配置失败:', error);
      return null;
    }
  }

  /**
   * 获取当前组的配置
   * @returns {Promise<object>} 组配置
   */
  async getGroupConfig() {
    const groupId = await this.getGroup();
    if (!groupId) return null;
    
    const experiment = await this.getExperimentConfig();
    if (!experiment || !experiment.groups) return null;
    
    return experiment.groups[groupId]?.config || null;
  }

  /**
   * 上报事件
   * @param {string} eventType 事件类型 (VIEW, CLICK, CONVERT)
   * @param {string} eventName 事件名称
   * @param {object} properties 事件属性
   */
  async reportEvent(eventType, eventName, properties) {
    try {
      const response = await fetch(`${this.apiBaseUrl}/data/event`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          experimentId: this.experimentId,
          visitorId: this.visitorId,
          eventType: eventType,
          eventName: eventName,
          properties: properties
        })
      });
      
      return await response.json();
    } catch (error) {
      console.error('上报事件失败:', error);
    }
  }

  /**
   * 上报浏览事件
   * @param {object} productData 商品数据
   */
  async reportView(productData) {
    return this.reportEvent('VIEW', 'product_view', {
      productId: productData.productId,
      productPrice: productData.productPrice,
      marketPrice: productData.marketPrice,
      productModel: productData.productModel,
      condition: productData.condition
    });
  }

  /**
   * 上报咨询事件
   * @param {object} clickData 点击数据
   */
  async reportClick(clickData) {
    return this.reportEvent('CLICK', 'contact_seller', {
      productId: clickData.productId,
      productPrice: clickData.productPrice
    });
  }

  /**
   * 上报成交事件（关键指标）
   * @param {object} transactionData 交易数据
   */
  async reportTransaction(transactionData) {
    const priceRatio = transactionData.marketPrice > 0 
      ? transactionData.transactionPrice / transactionData.marketPrice 
      : 0;
    
    return this.reportEvent('CONVERT', 'transaction_completed', {
      transactionId: transactionData.transactionId,
      productId: transactionData.productId,
      transactionPrice: transactionData.transactionPrice,  // 实际成交价格（核心指标）
      listPrice: transactionData.listPrice,
      marketPrice: transactionData.marketPrice,
      priceRatio: priceRatio
    });
  }
}

// 导出SDK
if (typeof module !== 'undefined' && module.exports) {
  module.exports = PiscesSDK;
}

// 浏览器环境
if (typeof window !== 'undefined') {
  window.PiscesSDK = PiscesSDK;
}
