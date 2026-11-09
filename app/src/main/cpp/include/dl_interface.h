#ifndef __DL_INTERFACE_H__
#define __DL_INTERFACE_H__
#ifdef __cplusplus
extern "C" {
#endif  // __cplusplus

#define MAX_ALGO_MODULE_NUM 16
#define MAX_IMG_NUM 5
#define MAX_OD_NUM 100
#define MAX_KP_NUM 64

typedef enum
{
    DL_IMG_DATA_TYPE_NONE = -1,
    DL_IMG_DATA_TYPE_BGR = 0,   /* BGR */
    DL_IMG_DATA_TYPE_RGB,       /* RGB */
    DL_IMG_DATA_TYPE_NV12,      /* NV12 */
    DL_IMG_DATA_TYPE_GRAY,
    DL_IMG_DATA_TYPE_MAX,
} DL_ImageDataType_e;

typedef enum 
{
    DL_ALGO_PROC_TYPE_NONE = -1,            /* 未知类型 */
    DL_ALGO_PROC_TYPE_FACE_DET = 0,         /* 人脸检测 */
    DL_ALGO_PROC_TYPE_FACE_REC = 1,         /* 人脸识别 */
    DL_ALGO_PROC_TYPE_SKELETON_KP = 2,      /* 骨骼关键点 */
} DL_AlgoProcType_e;

typedef enum
{
    DL_OD_NONE = -1,
    DL_OD_PERSON = 0x00,       /* 人 */
    DL_OD_FACE,
    DL_OD_MAX,
} DL_OD_ObjectType_e;

typedef enum
{
    DL_GENDER_NONE = -1,
    DL_GENDER_WOMAN = 0x00,
    DL_GENDER_MAN,
    DL_GENDER_MAX,
} DL_GenderType_e;

typedef enum
{
    DL_RACE_NONE = -1,
    DL_RACE_ASIAN = 0x00,
    DL_RACE_INDIAN,
    DL_RACE_BLACK,
    DL_RACE_WHITE,
    DL_RACE_MIDDLE_EASTERN,
    DL_RACE_LATINO_HISPANIC,
    DL_RACE_MAX,
} DL_RaceType_e;

typedef enum
{
    DL_EMOTION_NONE = -1,
    DL_EMOTION_ANGRY = 0x00,
    DL_EMOTION_DISGUST,
    DL_EMOTION_FEAR,
    DL_EMOTION_HAPPY,
    DL_EMOTION_SAD,
    DL_EMOTION_SURPRISE,
    DL_EMOTION_NEUTRAL,
    DL_EMOTION_MAX
} DL_EmotionType_e;

//图像结构体
struct DL_ImageData_t
{
    // 图像源
    int camIndex;
    // 处理类型(按位标记)，位标记类型见 DL_AlgoProcType_e
    unsigned int procType;
    // 源图宽度
    unsigned int srcWidth;
    // 源图高度
    unsigned int srcHeight;
    // dataBuf对应的图像数据类型
    DL_ImageDataType_e dataType;
    // 图像数据指针
    void* dataBuf;
    // 叠加调试图数据指针
    void* debugBuf;
    // 当前统一帧号，外部统一一个帧号
    unsigned long frameNo;
    // 当前帧率
    float frameRate;
    // 当前帧的时间戳，单位ms
    unsigned int timestamp;
};
typedef struct DL_ImageData_t DL_ImageData;
typedef DL_ImageData* DL_ImageDataPtr;

struct DL_SegResult_t
{
    // 图像源
    int camIndex;
    // 源图宽度
    unsigned int srcWidth;
    // 源图高度
    unsigned int srcHeight;
    // 是否从源图取的roi:0-否, 1-是
    unsigned char roiFlag;
    // 从源图取的roi区域, left top xy, right bottom xy
    unsigned int roi[4];
    // mask图宽度
    unsigned int maskWidth;
    // mask图高度
    unsigned int maskHeight;
    // mask图通道数
    // unsigned int maskChannel;
    // mask图数据指针, size=maskHeight*maskWidth
    const void* dataBuf;
    // 检测帧的时间戳，单位ms
    unsigned int timestamp;
};
typedef struct DL_SegResult_t DL_SegResult;
typedef DL_SegResult* DL_SegResultPtr;

//OD检测结果
struct DL_ODData_t
{
    // 目标id
    int id;
    // 目标类别
    DL_OD_ObjectType_e type;
    // 图像中目标框左上点x轴坐标
    unsigned int left;
    // 图像中目标框左上点y轴坐标
    unsigned int top;
    // 图像中目标框右下点x轴坐标
    unsigned int right;
    // 图像中目标框右下点y轴坐标
    unsigned int bottom;
    // 置信度
    float score;
    // 人脸属性（FACE_REC 开启且识别成功时有效，否则为 -1）
    int age;
    int gender;
    int race;
    int emotion;
    unsigned int numKp;
    unsigned int kpx[MAX_KP_NUM];
    unsigned int kpy[MAX_KP_NUM];
    // 检测帧的时间戳，单位ms
    unsigned int timestamp;
};
typedef struct DL_ODData_t DL_ODData;

struct DL_ODResult_t
{
    // 图像源
    int camIndex;
    // 目标数
    unsigned int numOD;
    // 目标信息
    DL_ODData ODInfo[MAX_OD_NUM];
};
typedef struct DL_ODResult_t DL_ODResult;
typedef DL_ODResult* DL_ODResultPtr;

struct DL_Result_t
{
    DL_ODResultPtr odOut[MAX_IMG_NUM];
    DL_SegResultPtr segOut[MAX_IMG_NUM];
};
typedef struct DL_Result_t DL_Result;
typedef DL_Result* DL_ResultPtr;

typedef struct DL_Config_t
{
    // 算法模块id
    unsigned int procId;
    // 处理类型(按位标记)，位标记类型见 DL_AlgoProcType_e
    unsigned int procType;
    // dnn模型根目录
    char modelRootPath[256];
}DL_Config;

/**
 * 算法初始化
 * @param[in] dlConfig: 算法配置
 * @return 0 if success
 */
int DL_Init(DL_Config dlConfig);

/**
 * 算法处理
 * @param[in] procId: 算法模块id
 * @param[in] inImage: 输入图像数据指针
 * @param[in] imgNum: 输入图像数
 * @param[out] dlResult: 检测结果
 * @return 0 if success
 */
int DL_Process(unsigned int procId, 
                DL_ImageDataPtr inImage[MAX_IMG_NUM], 
                int imgNum, 
                DL_ResultPtr dlResult);

/**
 * 算法退出
 * @param[in] procId: 算法模块id
 * @return 0 if success
 */
int DL_Exit(unsigned int procId);

#ifdef __cplusplus
}
#endif  // __cplusplus
#endif // __DL_INTERFACE_H__
