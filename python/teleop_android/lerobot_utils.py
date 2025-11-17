import math

import numpy as np
import transforms3d as t3d
from lerobot.utils.rotation import Rotation

#:

TF_RUB2FLU = np.array([[0, 0, -1, 0], [-1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 0, 1]])
TF_XYZW_TO_WXYZ = np.array([[0, 0, 0, 1], [1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0]])
TF_WXYZ_TO_XYZW = np.array([[0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1], [1, 0, 0, 0]])

#:


def are_close(a, b=None, lin_tol=1e-9, ang_tol=1e-9):
    """
    Check if two transformation matrices are close to each other within specified tolerances.

    REFS: https://github.com/SpesRobotics/teleop/blob/main/teleop/__init__.py

    Parameters:
        a (numpy.ndarray): The first transformation matrix.
        b (numpy.ndarray, optional): The second transformation matrix. If not provided, it defaults to the identity matrix.
        lin_tol (float, optional): The linear tolerance for closeness. Defaults to 1e-9.
        ang_tol (float, optional): The angular tolerance for closeness. Defaults to 1e-9.

    Returns:
        bool: True if the matrices are close, False otherwise.
    """
    if b is None:
        b = np.eye(4)
    d = np.linalg.inv(a) @ b
    if not np.allclose(d[:3, 3], np.zeros(3), atol=lin_tol):
        return False
    yaw = math.atan2(d[1, 0], d[0, 0])
    pitch = math.asin(-d[2, 0])
    roll = math.atan2(d[2, 1], d[2, 2])
    rpy = np.array([roll, pitch, yaw])
    return np.allclose(rpy, np.zeros(3), atol=ang_tol)


def slerp(q1, q2, t):
    """
    Spherical linear interpolation between two quaternions.

    REFS: https://github.com/SpesRobotics/teleop/blob/main/teleop/__init__.py
    """
    q1 = q1 / np.linalg.norm(q1)
    q2 = q2 / np.linalg.norm(q2)

    dot = np.dot(q1, q2)

    # If the dot product is negative, use the shortest path
    if dot < 0.0:
        q2 = -q2
        dot = -dot

    DOT_THRESHOLD = 0.9995
    if dot > DOT_THRESHOLD:
        # Linear interpolation fallback for nearly identical quaternions
        result = q1 + t * (q2 - q1)
        return result / np.linalg.norm(result)

    theta_0 = np.arccos(dot)
    theta = theta_0 * t

    q3 = q2 - q1 * dot
    q3 = q3 / np.linalg.norm(q3)

    return q1 * np.cos(theta) + q3 * np.sin(theta)


def interpolate_transforms(T1, T2, alpha):
    """
    Interpolate between two 4x4 transformation matrices using SLERP + linear translation.

    REFS: https://github.com/SpesRobotics/teleop/blob/main/teleop/__init__.py

    Args:
        T1 (np.ndarray): Start transform (4x4)
        T2 (np.ndarray): End transform (4x4)
        alpha (float): Interpolation factor [0, 1]

    Returns:
        np.ndarray: Interpolated transform (4x4)
    """
    assert T1.shape == (4, 4) and T2.shape == (4, 4)
    assert 0.0 <= alpha <= 1.0

    # Translation
    t1 = T1[:3, 3]
    t2 = T2[:3, 3]
    t_interp = (1 - alpha) * t1 + alpha * t2

    # Rotation
    R1 = T1[:3, :3]
    R2 = T2[:3, :3]
    q1 = t3d.quaternions.mat2quat(R1)
    q2 = t3d.quaternions.mat2quat(R2)

    # SLERP
    q_interp = slerp(q1, q2, alpha)
    R_interp = t3d.quaternions.quat2mat(q_interp)

    # Final transform
    T_interp = np.eye(4)
    T_interp[:3, :3] = R_interp
    T_interp[:3, 3] = t_interp

    return T_interp


def compute_wrist_deltas_from_phone_and_arm(
    orientation_matrix_phone: np.ndarray,
    orientation_matrix_arm: np.ndarray,
    orientation_matrix_phone_init: np.ndarray,
    orientation_matrix_arm_init: np.ndarray,
) -> tuple[float, float]:
    """
    Compute wrist flex and roll angles from phone and arm rotations.

    Args:
        orientation_matrix_phone: Current phone rotation in world frame (3x3)
        orientation_matrix_arm: Current lower arm rotation in world frame (3x3)
        orientation_matrix_phone_init: Initial phone rotation in world frame (3x3)
        orientation_matrix_arm_init: Initial lower arm rotation in world frame (3x3)

    Returns:
        (delta_rad_pitch, delta_rad_roll): Wrist delta angles in radians
    """
    # Compute phone's orientation in the lower arm frame coordinates
    orientation_wrist = orientation_matrix_arm.T @ orientation_matrix_phone
    orientation_wrist_init = (
        orientation_matrix_arm_init.T @ orientation_matrix_phone_init
    )

    # Compute delta rotation: how the phone rotated since calibration, expressed in the lower arm frame
    delta_orientation_wrist = orientation_wrist_init.T @ orientation_wrist

    # Extract pitch using wrist joint convention (relative to arm)
    # Note that the lower arm frame coordinates are DLF instead of FLU
    _, delta_rad_pitch, _ = t3d.euler.mat2euler(delta_orientation_wrist, axes="sxyz")

    # Compute roll independently in world frame (not relative to arm roll)
    # Compute phone's rotation delta in world frame
    delta_orientation_phone_world = (
        orientation_matrix_phone_init.T @ orientation_matrix_phone
    )

    # Extract roll from the phone's rotation delta in world frame, this gives roll independent of arm roll
    delta_rad_roll, _, _ = t3d.euler.mat2euler(
        delta_orientation_phone_world, axes="sxyz"
    )

    return delta_rad_pitch, delta_rad_roll


def matrix_t3d_to_rotation(orientation_matrix) -> Rotation:
    orientation_quaternion_wxyz = t3d.quaternions.mat2quat(orientation_matrix)
    orientation_quaternion_xyzw = TF_WXYZ_TO_XYZW @ orientation_quaternion_wxyz
    return Rotation.from_quat(orientation_quaternion_xyzw)
