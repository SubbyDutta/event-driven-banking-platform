/**
 * Framer Motion Animation Variants
 * Production-Ready, Optimized for 60fps
 */

// ========================================
// BASIC TRANSITIONS
// ========================================

export const transitions = {
  fast: {
    duration: 0.15,
    ease: [0.4, 0, 0.2, 1],
  },
  base: {
    duration: 0.2,
    ease: [0.4, 0, 0.2, 1],
  },
  slow: {
    duration: 0.3,
    ease: [0.4, 0, 0.2, 1],
  },
  slower: {
    duration: 0.4,
    ease: [0.4, 0, 0.2, 1],
  },
  spring: {
    type: 'spring',
    stiffness: 300,
    damping: 30,
  },
  springBouncy: {
    type: 'spring',
    stiffness: 400,
    damping: 25,
  },
  premium: {
    type: 'spring',
    stiffness: 350,
    damping: 30,
    mass: 1,
  },
};

// ========================================
// FADE ANIMATIONS
// ========================================

export const fadeIn = {
  initial: { opacity: 0 },
  animate: { opacity: 1 },
  exit: { opacity: 0 },
  transition: transitions.base,
};

export const fadeInUp = {
  initial: { opacity: 0, y: 20 },
  animate: { opacity: 1, y: 0 },
  exit: { opacity: 0, y: 20 },
  transition: transitions.slow,
};

export const fadeInDown = {
  initial: { opacity: 0, y: -20 },
  animate: { opacity: 1, y: 0 },
  exit: { opacity: 0, y: -20 },
  transition: transitions.slow,
};

export const fadeInLeft = {
  initial: { opacity: 0, x: -20 },
  animate: { opacity: 1, x: 0 },
  exit: { opacity: 0, x: -20 },
  transition: transitions.slow,
};

export const fadeInRight = {
  initial: { opacity: 0, x: 20 },
  animate: { opacity: 1, x: 0 },
  exit: { opacity: 0, x: 20 },
  transition: transitions.slow,
};

// ========================================
// SCALE ANIMATIONS
// ========================================

export const scaleIn = {
  initial: { opacity: 0, scale: 0.95 },
  animate: { opacity: 1, scale: 1 },
  exit: { opacity: 0, scale: 0.95 },
  transition: transitions.base,
};

export const scaleInSpring = {
  initial: { opacity: 0, scale: 0.9 },
  animate: { opacity: 1, scale: 1 },
  exit: { opacity: 0, scale: 0.9 },
  transition: transitions.springBouncy,
};

// ========================================
// STAGGER ANIMATIONS (for lists)
// ========================================

export const staggerContainer = {
  initial: {},
  animate: {
    transition: {
      staggerChildren: 0.05,
      delayChildren: 0.1,
    },
  },
  exit: {
    transition: {
      staggerChildren: 0.02,
      staggerDirection: -1,
    },
  },
};

export const staggerItem = {
  initial: { opacity: 0, y: 10 },
  animate: { opacity: 1, y: 0 },
  exit: { opacity: 0, y: 10 },
};

export const staggerItemLeft = {
  initial: { opacity: 0, x: -10 },
  animate: { opacity: 1, x: 0 },
  exit: { opacity: 0, x: -10 },
};

// ========================================
// CARD ANIMATIONS
// ========================================

export const cardHover = {
  rest: {
    scale: 1,
    y: 0,
    boxShadow: '0 2px 8px rgba(0, 0, 0, 0.04)',
  },
  hover: {
    scale: 1.02,
    y: -4,
    boxShadow: '0 8px 24px rgba(0, 0, 0, 0.12)',
    transition: transitions.fast,
  },
  tap: {
    scale: 0.98,
    y: 0,
  },
};

export const cardPremiumHover = {
  rest: {
    scale: 1,
    y: 0,
    boxShadow: '0 2px 8px rgba(0, 0, 0, 0.04)',
  },
  hover: {
    scale: 1.02,
    y: -4,
    boxShadow: '0 12px 32px rgba(0, 0, 0, 0.12)',
    transition: { type: 'spring', stiffness: 350, damping: 25 },
  },
  tap: {
    scale: 0.98,
    y: 0,
    transition: { type: 'spring', stiffness: 400, damping: 25 },
  },
};

export const cardPress = {
  whileHover: { scale: 1.02, y: -2 },
  whileTap: { scale: 0.98, y: 0 },
  transition: transitions.fast,
};

// ========================================
// BUTTON ANIMATIONS
// ========================================

export const buttonHover = {
  whileHover: { scale: 1.05, y: -2 },
  whileTap: { scale: 0.95 },
  transition: transitions.fast,
};

export const buttonPress = {
  whileTap: { scale: 0.95 },
  transition: transitions.fast,
};

// ========================================
// SLIDE ANIMATIONS
// ========================================

export const slideUp = {
  initial: { y: '100%', opacity: 0 },
  animate: { y: 0, opacity: 1 },
  exit: { y: '100%', opacity: 0 },
  transition: transitions.slow,
};

export const slideDown = {
  initial: { y: '-100%', opacity: 0 },
  animate: { y: 0, opacity: 1 },
  exit: { y: '-100%', opacity: 0 },
  transition: transitions.slow,
};

export const slideLeft = {
  initial: { x: '100%', opacity: 0 },
  animate: { x: 0, opacity: 1 },
  exit: { x: '100%', opacity: 0 },
  transition: transitions.slow,
};

export const slideRight = {
  initial: { x: '-100%', opacity: 0 },
  animate: { x: 0, opacity: 1 },
  exit: { x: '-100%', opacity: 0 },
  transition: transitions.slow,
};

// ========================================
// MODAL/OVERLAY ANIMATIONS
// ========================================

export const modalBackdrop = {
  initial: { opacity: 0 },
  animate: { opacity: 1 },
  exit: { opacity: 0 },
  transition: transitions.base,
};

export const modalContent = {
  initial: { opacity: 0, scale: 0.95, y: 20 },
  animate: { opacity: 1, scale: 1, y: 0 },
  exit: { opacity: 0, scale: 0.95, y: 20 },
  transition: transitions.slow,
};

// ========================================
// NOTIFICATION/TOAST ANIMATIONS
// ========================================

export const toastSlideIn = {
  initial: { opacity: 0, y: -100, scale: 0.95 },
  animate: { opacity: 1, y: 0, scale: 1 },
  exit: { opacity: 0, y: -100, scale: 0.95 },
  transition: transitions.springBouncy,
};

// ========================================
// LAYOUT ANIMATIONS
// ========================================

export const expandCollapse = {
  initial: { height: 0, opacity: 0 },
  animate: { height: 'auto', opacity: 1 },
  exit: { height: 0, opacity: 0 },
  transition: transitions.base,
};

export const expandWidth = {
  initial: { width: 0, opacity: 0 },
  animate: { width: 'auto', opacity: 1 },
  exit: { width: 0, opacity: 0 },
  transition: transitions.base,
};

// ========================================
// PAGE TRANSITIONS
// ========================================

export const pageTransition = {
  initial: { opacity: 0, y: 20 },
  animate: { opacity: 1, y: 0 },
  exit: { opacity: 0, y: -20 },
  transition: transitions.slower,
};

export const pageSlide = {
  initial: { opacity: 0, x: 100 },
  animate: { opacity: 1, x: 0 },
  exit: { opacity: 0, x: -100 },
  transition: transitions.slower,
};

// ========================================
// ROTATE ANIMATIONS
// ========================================

export const rotateIn = {
  initial: { opacity: 0, rotate: -10, scale: 0.95 },
  animate: { opacity: 1, rotate: 0, scale: 1 },
  exit: { opacity: 0, rotate: 10, scale: 0.95 },
  transition: transitions.slow,
};

// ========================================
// CUSTOM ANIMATIONS
// ========================================

export const pulse = {
  animate: {
    scale: [1, 1.05, 1],
    opacity: [1, 0.8, 1],
  },
  transition: {
    duration: 2,
    repeat: Infinity,
    ease: 'easeInOut',
  },
};

export const float = {
  animate: {
    y: [0, -10, 0],
  },
  transition: {
    duration: 3,
    repeat: Infinity,
    ease: 'easeInOut',
  },
};

export const shimmer = {
  animate: {
    backgroundPosition: ['0% 50%', '100% 50%', '0% 50%'],
  },
  transition: {
    duration: 3,
    repeat: Infinity,
    ease: 'linear',
  },
};

// ========================================
// UTILITY FUNCTIONS
// ========================================

/**
 * Creates a stagger animation for list items
 * @param {number} delay - Delay between items (default: 0.05)
 * @param {number} initialDelay - Initial delay before animation starts (default: 0)
 */
export const createStaggerAnimation = (delay = 0.05, initialDelay = 0) => ({
  container: {
    animate: {
      transition: {
        staggerChildren: delay,
        delayChildren: initialDelay,
      },
    },
  },
  item: {
    initial: { opacity: 0, y: 10 },
    animate: { opacity: 1, y: 0 },
  },
});

/**
 * Creates a custom fade animation with configurable distance
 * @param {number} distance - Distance to move (default: 20)
 * @param {string} direction - Direction: 'up', 'down', 'left', 'right' (default: 'up')
 */
export const createFadeAnimation = (distance = 20, direction = 'up') => {
  const getInitial = () => {
    switch (direction) {
      case 'up':
        return { opacity: 0, y: distance };
      case 'down':
        return { opacity: 0, y: -distance };
      case 'left':
        return { opacity: 0, x: distance };
      case 'right':
        return { opacity: 0, x: -distance };
      default:
        return { opacity: 0 };
    }
  };

  return {
    initial: getInitial(),
    animate: { opacity: 1, y: 0, x: 0 },
    exit: getInitial(),
    transition: transitions.slow,
  };
};

/**
 * Optimized animation for performance
 * Uses GPU-accelerated properties only (transform, opacity)
 */
export const gpuOptimized = {
  initial: { opacity: 0, scale: 0.95 },
  animate: { opacity: 1, scale: 1 },
  exit: { opacity: 0, scale: 0.95 },
  transition: {
    ...transitions.base,
    // Force GPU acceleration
    transform: { type: 'spring' },
  },
};
