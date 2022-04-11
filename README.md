# BaiduNetdiskFetchCodeCrack

![GitHub](https://img.shields.io/github/license/GuangPingLin/BaiduPanFetchCrack)

1. 免责声明

	**本项目旨在学习多线程并发以及DSF算法的一点实践, 不可用于商业和个人其他意图. 若使用不当, 均由使用者承担.**
	
	**本项目只能用于学习和交流, 有能力的请支持百度网盘资源分享者或购买其资源.**
	
	**本项目严禁用于破解倒卖百度网盘资源分享者的资源, 否则产生法律责任由使用者承担.**
	
2. 破解思路

	破解算法: 使用DSF算法枚举全部提取码的可能性, 取得提取码字典, 然后多线程对提取码字典进行 HTTP POST 请求验证提取码, 只要时间足够, 终可暴力破解.
	
	破解难点: 设验证提取码x个/s, 则需要约 f(x) = (36 * 36 * 36 * 36) / x / 3600 / 24 = (486 / 25x) 天, 易得x >= 0, f(x) 单调递减,即 x 越大, 则耗时越短.百度网盘限制提取码4次/IP地址, 超过4次会出现验证码, 或IP地址被拉入黑名单, 百度网盘拉入黑名单后的IP地址的提取请求响应404.

	破解方法: 为极大地降低耗时, 采用多线程并发请求, 且线程管理和HTTP连接管理均使用连接池管理, 降低往复创建线程和请求连接的耗时, 最低提取码验证并发要求为10个/s, 10个/s最长耗时为 (486 / 250) 约 2 天; 通过在HTTP上挂代理进行请求, 避开了验证码和黑名单.

3. 提取码字典

	百度网盘提取码规则: [0-9a-z]{4}, 每1位有36种可能.
	
	字典大小: 36 * 36 * 36 * 36 = 1,679,616个, 排除 0000 1111 2222 ... zzzz 36个, 则有1,679,580个.
	
	字典存储默认路径: /BaiduNetdiskFetchCodeCrack/password.txt
	
	![提取码字典](https://user-images.githubusercontent.com/43131785/162620894-bbc34322-4c6c-4062-a8c3-45729ba14a25.png)
	
4. 破解过程的日志

	验证过的提取码存储路径: /BaiduNetdiskFetchCodeCrack/passwordHasTest.txt, 形如——"提取码错误: YYYY".

	破解成功的结果存储路径: 同上, 形如——"https://pan.baidu.com/s/1XXX 的提取码: YYYY".若不存在, 则意味着重新破解, **对于不同的百度网盘分享链接, 首次运行时必须删除此文件**.
	
	本项目的日志存储路径: /BaiduNetdiskFetchCodeCrack/logs, 其中/BaiduNetdiskFetchCodeCrack/logs/appError.log 为程序报错的日志; /BaiduNetdiskFetchCodeCrack/logs/appInfo.log 为程序提取码验证的记录.项目日志可删除, 不影响破解.
	
	日志示例: 示例破解的是一份考研-专硕-英语二的课程百度网盘分享链接的提取码, 如下所示

	![破解过程日志](https://user-images.githubusercontent.com/43131785/162657406-1bb89502-e3f3-4118-b377-7cb3ba4ba1a4.png)

5. 破解耗时

	本项目按照物理双核CPU, 10个线程, 正常网络状态下, 提取码验证可达28个/s, 最长约7个小时破解完成, 通常3.5个小时, 因为并不需要将整个字典都验证一遍.
	
6. 使用简介

	**使用过程如遇到问题, 可添加我微信(nibbonty), 很高兴能帮到您.**

	a. clone/download 本项目, 配置代理, 本项目免费提供 350M 的代理访问流量, 再流量使用完之前, 可无需配置代理; 若运行出错, 很可能是提供的代理流量已用完, 此时需要自行配置代理.

	b. 本项目是简单的Java应用程序.使用IDE(如IDEA)导入项目;
	
	c. 找到 "src/main/java/priv/light/baidu/BaiDuPanFetchCrack.java" 文件;
	
	d. 将局部变量 "panUri" 的构造参数值修改为需要破解的百度网盘分享链接, 然后运行 main 方法即可.
	
	e. 等待程序破解完成, 如需中途退出, 手动停止运行后, 需要等待一分钟左右, 程序对破解过程进行记录, 以在下一次运行时无需重新验证.
	
