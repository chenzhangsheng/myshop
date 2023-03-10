package cn.lili.modules.store.serviceimpl;

import cn.hutool.core.date.DateTime;
import cn.hutool.core.text.CharSequenceUtil;
import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.properties.RocketmqCustomProperties;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.utils.BeanUtil;
import cn.lili.common.vo.PageVO;
import cn.lili.modules.goods.entity.dos.GoodsSku;
import cn.lili.modules.goods.service.GoodsService;
import cn.lili.modules.goods.service.GoodsSkuService;
import cn.lili.modules.member.entity.dos.Clerk;
import cn.lili.modules.member.entity.dos.FootPrint;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.entity.dto.ClerkAddDTO;
import cn.lili.modules.member.entity.dto.CollectionDTO;
import cn.lili.modules.member.service.ClerkService;
import cn.lili.modules.member.service.FootprintService;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.store.entity.dos.Store;
import cn.lili.modules.store.entity.dos.StoreDetail;
import cn.lili.modules.store.entity.dto.*;
import cn.lili.modules.store.entity.enums.StoreStatusEnum;
import cn.lili.modules.store.entity.vos.StoreSearchParams;
import cn.lili.modules.store.entity.vos.StoreVO;
import cn.lili.modules.store.mapper.StoreMapper;
import cn.lili.modules.store.service.StoreDetailService;
import cn.lili.modules.store.service.StoreService;
import cn.lili.mybatis.util.PageUtil;
import cn.lili.rocketmq.RocketmqSendCallbackBuilder;
import cn.lili.rocketmq.tags.StoreTagsEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ?????????????????????
 *
 * @author pikachu
 * @since 2020-03-07 16:18:56
 */
@Service
public class StoreServiceImpl extends ServiceImpl<StoreMapper, Store> implements StoreService {

    /**
     * ??????
     */
    @Autowired
    private MemberService memberService;

    /**
     * ??????
     */
    @Autowired
    private ClerkService clerkService;
    /**
     * ??????
     */
    @Autowired
    private GoodsService goodsService;

    @Autowired
    private GoodsSkuService goodsSkuService;
    /**
     * ????????????
     */
    @Autowired
    private StoreDetailService storeDetailService;

    @Autowired
    private RocketmqCustomProperties rocketmqCustomProperties;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private FootprintService footprintService;

    @Autowired
    private Cache cache;

    @Override
    public IPage<StoreVO> findByConditionPage(StoreSearchParams storeSearchParams, PageVO page) {
        return this.baseMapper.getStoreList(PageUtil.initPage(page), storeSearchParams.queryWrapper());
    }

    @Override
    public StoreVO getStoreDetail() {
        AuthUser currentUser = Objects.requireNonNull(UserContext.getCurrentUser());
        StoreVO storeVO = this.baseMapper.getStoreDetail(currentUser.getStoreId());
        storeVO.setNickName(currentUser.getNickName());
        return storeVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Store add(AdminStoreApplyDTO adminStoreApplyDTO) {

        //??????????????????????????????
        QueryWrapper<Store> queryWrapper = Wrappers.query();
        queryWrapper.eq("store_name", adminStoreApplyDTO.getStoreName());
        if (this.getOne(queryWrapper) != null) {
            throw new ServiceException(ResultCode.STORE_NAME_EXIST_ERROR);
        }

        Member member = memberService.getById(adminStoreApplyDTO.getMemberId());
        //????????????????????????
        if (member == null) {
            throw new ServiceException(ResultCode.USER_NOT_EXIST);
        }
        //????????????????????????
        if (Boolean.TRUE.equals(member.getHaveStore())) {
            throw new ServiceException(ResultCode.STORE_APPLY_DOUBLE_ERROR);
        }

        //????????????
        Store store = new Store(member, adminStoreApplyDTO);
        this.save(store);

        //??????????????????????????????????????????????????????????????????????????????????????????
        StoreDetail storeDetail = new StoreDetail(store, adminStoreApplyDTO);

        storeDetailService.save(storeDetail);

        //????????????-????????????
        memberService.update(new LambdaUpdateWrapper<Member>()
                .eq(Member::getId, member.getId())
                .set(Member::getHaveStore, true)
                .set(Member::getStoreId, store.getId()));
        return store;

    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Store edit(StoreEditDTO storeEditDTO) {
        if (storeEditDTO != null) {
            //???????????????????????????
            Store storeTmp = getOne(new QueryWrapper<Store>().eq("store_name", storeEditDTO.getStoreName()));
            if (storeTmp != null && !CharSequenceUtil.equals(storeTmp.getId(), storeEditDTO.getStoreId())) {
                throw new ServiceException(ResultCode.STORE_NAME_EXIST_ERROR);
            }
            //????????????????????????
            updateStoreDetail(storeEditDTO);
            //??????????????????
            return updateStore(storeEditDTO);
        } else {
            throw new ServiceException(ResultCode.STORE_NOT_EXIST);
        }
    }

    /**
     * ????????????????????????
     *
     * @param storeEditDTO ??????????????????
     */
    private Store updateStore(StoreEditDTO storeEditDTO) {
        Store store = this.getById(storeEditDTO.getStoreId());
        if (store != null) {
            BeanUtil.copyProperties(storeEditDTO, store);
            store.setId(storeEditDTO.getStoreId());
            boolean result = this.updateById(store);
            if (result) {
                storeDetailService.updateStoreGoodsInfo(store);
            }
            String destination = rocketmqCustomProperties.getStoreTopic() + ":" + StoreTagsEnum.EDIT_STORE_SETTING.name();
            //??????????????????mq??????
            rocketMQTemplate.asyncSend(destination, store, RocketmqSendCallbackBuilder.commonCallback());
        }

        cache.remove(CachePrefix.STORE.getPrefix() + storeEditDTO.getStoreId());
        return store;
    }

    /**
     * ????????????????????????
     *
     * @param storeEditDTO ??????????????????
     */
    private void updateStoreDetail(StoreEditDTO storeEditDTO) {
        StoreDetail storeDetail = new StoreDetail();
        BeanUtil.copyProperties(storeEditDTO, storeDetail);
        storeDetailService.update(storeDetail, new QueryWrapper<StoreDetail>().eq("store_id", storeEditDTO.getStoreId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean audit(String id, Integer passed) {
        Store store = this.getById(id);
        if (store == null) {
            throw new ServiceException(ResultCode.STORE_NOT_EXIST);
        }
        if (passed == 0) {
            store.setStoreDisable(StoreStatusEnum.OPEN.value());
            //???????????? ??????????????????
            Member member = memberService.getById(store.getMemberId());
            member.setHaveStore(true);
            member.setStoreId(id);
            memberService.updateById(member);
            //????????????
            ClerkAddDTO clerkAddDTO = new ClerkAddDTO();
            clerkAddDTO.setMemberId(member.getId());
            clerkAddDTO.setIsSuper(true);
            clerkAddDTO.setShopkeeper(true);
            clerkAddDTO.setStoreId(id);
            clerkService.saveClerk(clerkAddDTO);
            //????????????????????????
            storeDetailService.update(new LambdaUpdateWrapper<StoreDetail>()
                    .eq(StoreDetail::getStoreId, id)
                    .set(StoreDetail::getSettlementDay, new DateTime()));
        } else {
            store.setStoreDisable(StoreStatusEnum.REFUSED.value());
        }

        return this.updateById(store);
    }

    @Override
    public boolean disable(String id) {
        Store store = this.getById(id);
        if (store != null) {
            store.setStoreDisable(StoreStatusEnum.CLOSED.value());

            //???????????????????????????
            goodsService.underStoreGoods(id);
            return this.updateById(store);
        }

        throw new ServiceException(ResultCode.STORE_NOT_EXIST);
    }

    @Override
    public boolean enable(String id) {
        Store store = this.getById(id);
        if (store != null) {
            store.setStoreDisable(StoreStatusEnum.OPEN.value());
            return this.updateById(store);
        }
        throw new ServiceException(ResultCode.STORE_NOT_EXIST);
    }

    @Override
    public boolean applyFirstStep(StoreCompanyDTO storeCompanyDTO) {
        //???????????????????????????
        Store store = getStoreByMember();

        //??????????????????????????????
        if (store == null) {
            AuthUser authUser = Objects.requireNonNull(UserContext.getCurrentUser());
            Member member = memberService.getById(authUser.getId());
            //????????????????????????
            store = new Store(member);
            BeanUtil.copyProperties(storeCompanyDTO, store);
            this.save(store);
            StoreDetail storeDetail = new StoreDetail();
            storeDetail.setStoreId(store.getId());
            BeanUtil.copyProperties(storeCompanyDTO, storeDetail);
            return storeDetailService.save(storeDetail);
        } else {

            //?????????????????????
            checkStoreStatus(store);
            //???????????? ?????????????????????
            BeanUtil.copyProperties(storeCompanyDTO, store);
            this.updateById(store);
            //??????????????????????????????????????????????????????????????????????????????????????????
            StoreDetail storeDetail = storeDetailService.getStoreDetail(store.getId());
            //??????????????????????????????new ?????????????????????????????????????????????
            if (storeDetail == null) {
                storeDetail = new StoreDetail();
                storeDetail.setStoreId(store.getId());
                BeanUtil.copyProperties(storeCompanyDTO, storeDetail);
                return storeDetailService.save(storeDetail);
            } else {
                BeanUtil.copyProperties(storeCompanyDTO, storeDetail);
                return storeDetailService.updateById(storeDetail);
            }
        }
    }

    @Override
    public boolean applySecondStep(StoreBankDTO storeBankDTO) {

        //???????????????????????????
        Store store = getStoreByMember();
        //?????????????????????
        checkStoreStatus(store);
        StoreDetail storeDetail = storeDetailService.getStoreDetail(store.getId());
        //???????????????????????????
        BeanUtil.copyProperties(storeBankDTO, storeDetail);
        return storeDetailService.updateById(storeDetail);
    }

    @Override
    public boolean applyThirdStep(StoreOtherInfoDTO storeOtherInfoDTO) {
        //???????????????????????????
        Store store = getStoreByMember();

        //?????????????????????
        checkStoreStatus(store);
        BeanUtil.copyProperties(storeOtherInfoDTO, store);
        this.updateById(store);

        StoreDetail storeDetail = storeDetailService.getStoreDetail(store.getId());
        //???????????????????????????
        BeanUtil.copyProperties(storeOtherInfoDTO, storeDetail);
        //????????????????????????
        storeDetail.setGoodsManagementCategory(storeOtherInfoDTO.getGoodsManagementCategory());
        //????????????????????????????????????????????????????????????
        storeDetail.setStockWarning(10);
        //????????????????????????
        storeDetailService.updateById(storeDetail);
        //??????????????????,??????????????????
        store.setStoreName(storeOtherInfoDTO.getStoreName());
        store.setStoreDisable(StoreStatusEnum.APPLYING.name());
        store.setStoreCenter(storeOtherInfoDTO.getStoreCenter());
        store.setStoreDesc(storeOtherInfoDTO.getStoreDesc());
        store.setStoreLogo(storeOtherInfoDTO.getStoreLogo());
        return this.updateById(store);
    }

    /**
     * ??????????????? ?????????????????????????????????
     *
     * @param store ??????
     */
    private void checkStoreStatus(Store store) {

        //???????????????????????????????????????????????????????????????????????????????????????
        if (store.getStoreDisable().equals(StoreStatusEnum.APPLY.name()) || store.getStoreDisable().equals(StoreStatusEnum.APPLYING.name())) {
            return;
        } else {
            throw new ServiceException(ResultCode.STORE_STATUS_ERROR);
        }

    }

    @Override
    public void updateStoreGoodsNum(String storeId, Long num) {
        //????????????????????????
        this.update(new LambdaUpdateWrapper<Store>()
                .set(Store::getGoodsNum, num)
                .eq(Store::getId, storeId));
    }

    @Override
    public void updateStoreCollectionNum(CollectionDTO collectionDTO) {
        baseMapper.updateCollection(collectionDTO.getId(), collectionDTO.getNum());
    }

    @Override
    public void storeToClerk() {
        //?????????????????????????????????????????????????????????
        clerkService.remove(new LambdaQueryWrapper<Clerk>().eq(Clerk::getShopkeeper, true));
        List<Clerk> clerkList = new ArrayList<>();
        //????????????????????????
        for (Store store : this.list(new LambdaQueryWrapper<Store>().eq(Store::getDeleteFlag, false).eq(Store::getStoreDisable, StoreStatusEnum.OPEN.name()))) {
            clerkList.add(new Clerk(store));
        }
        clerkService.saveBatch(clerkList);
    }

    @Override
    public List<GoodsSku> getToMemberHistory(String memberId) {
        AuthUser currentUser = UserContext.getCurrentUser();
        List<String> skuIdList = new ArrayList<>();
        for (FootPrint footPrint : footprintService.list(new LambdaUpdateWrapper<FootPrint>().eq(FootPrint::getStoreId, currentUser.getStoreId()).eq(FootPrint::getMemberId, memberId))) {
            if(footPrint.getSkuId() != null){
                skuIdList.add(footPrint.getSkuId());
            }
        }
        return goodsSkuService.getGoodsSkuByIdFromCache(skuIdList);
    }

    /**
     * ?????????????????????????????????
     *
     * @return ????????????
     */
    private Store getStoreByMember() {
        LambdaQueryWrapper<Store> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        if (UserContext.getCurrentUser() != null) {
            lambdaQueryWrapper.eq(Store::getMemberId, UserContext.getCurrentUser().getId());
        }
        return this.getOne(lambdaQueryWrapper, false);
    }

}