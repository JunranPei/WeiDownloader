import { replay, scripts } from './engine.mjs';

const token = 'mock-note-token';
const titles = [
  '吴昕好久不见的短发🕊️', '文淇银蓝色眼妆 化妆师本人亲自揭秘😎✌️',
  '头发卷的不要太听话🍄给文淇卷的松茸感短发', '给文淇画的💋顶级辣感红唇烟熏妆',
  '给吴昕剪的新短发｜米兰时装周', '吴昕米兰时装周🦋机械姬也会梦见电子蝴蝶吗',
  '提问🙋陈丽君和骨相化妆适配度！', '文淇｜秋日棕调里的水晶眼妆✨',
  '实实在在 给女明星用空瓶的妆前分享🤨', '给文淇画的自然丰收系野生感妆容👩🏻‍🌾🍅',
  '给吴昕的自然感卷发👩‍🦱', '给文淇画的 梦核初夏💚清透感妆容',
  '秋日妆容的细节分享', '舞台妆光泽感测试',
];
const id = i => `60f9bfde158b4ae9bd0e81e6${String(i + 1).padStart(2, '0')}aabfb4`;
const cover = i => `https://sns-webpic-qc.xhscdn.com/mock/${id(i)}.webp`;
export const mockWorks = titles.map((title, i) => ({ id: id(i), title, cover: cover(i), token, video: i % 3 === 0 }));
export const mockPages = [
  { cursor: 'mock-cursor-1', hasMore: true, works: mockWorks.slice(0, 8) },
  { cursor: '', hasMore: false, works: mockWorks.slice(8) },
];
export const mockDetails = Object.fromEntries(mockWorks.map(work => [work.id, {
  id: work.id, title: work.title, imageList: [{ infoList: [{ imageScene: 'H5_DTL', url: work.cover }], width: 1080, height: 1440 }],
}]));

export function mockProfileHtml() {
  const state = { profile: { noteData: mockWorks.map(work => ({ id: work.id, displayTitle: work.title, xsecToken: work.token, cover: { urlDefault: work.cover }, type: work.video ? 'video' : 'normal' })) } };
  return `<!doctype html><html><body><main id="app"></main><script>window.__INITIAL_STATE__=${JSON.stringify(state)}</script></body></html>`;
}

export async function runMock() {
  const code = await scripts();
  const result = await replay(mockProfileHtml(), 'https://www.xiaohongshu.com/user/profile/mock', code.extract);
  return { ...result, provenance: 'mock', apiPages: mockPages.map(page => ({ cursor: page.cursor, hasMore: page.hasMore, count: page.works.length })), details: mockDetails };
}
